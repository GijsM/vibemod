#!/usr/bin/env node
// Usage: node smoke-bot.js <mc-version> [host] [port]
//
// Joins the smoke server as a headless player and exercises the chat renderer
// the way a person would: open the browser, open the settings form, flip a
// toggle, open the make form and type a value into a captured input. A screen
// only renders for a player, so this is the only way to gate the chat UI end to
// end (ARCHITECTURE-V2 §3.2, §9 Phase C).
//
// Every clickable line the renderer emits carries a `run_command` click event
// pointing at `/vibe ui <token>`, so the bot can "click" by running the command
// it finds in the message JSON - exactly what a player's client does.

const mineflayer = require('mineflayer');

const version = process.argv[2] || '1.20.6';
const host = process.argv[3] || '127.0.0.1';
const port = parseInt(process.argv[4] || '25565', 10);
const USERNAME = 'SmokeBot';

let failures = 0;

function check(label, ok) {
  console.log(`  ${ok ? 'ok' : 'FAIL'}: ${label}`);
  if (!ok) {
    failures++;
  }
}

/** Every `run_command` value in a chat message's JSON, in order. */
function runCommands(json) {
  const out = [];
  const walk = (node) => {
    if (!node || typeof node !== 'object') {
      return;
    }
    if (Array.isArray(node)) {
      node.forEach(walk);
      return;
    }
    const click = node.clickEvent || node.click_event;
    if (click && (click.action === 'run_command' || click.action === 'runCommand')) {
      const value = click.value !== undefined ? click.value : click.command;
      if (typeof value === 'string') {
        out.push(value);
      }
    }
    if (node.extra) {
      walk(node.extra);
    }
    if (node.with) {
      walk(node.with);
    }
    if (node.translate === undefined && node.text === undefined && node.extra === undefined) {
      Object.values(node).forEach((v) => {
        if (v && typeof v === 'object') {
          walk(v);
        }
      });
    }
  };
  walk(json);
  return out;
}

/**
 * When the server has the dialog API, `/vibe` must open a real dialog rather
 * than print a chat block - so the gate watches for the clientbound
 * show_dialog packet instead of parsing text. Pass `--dialogs` to assert that.
 */
const expectDialogs = process.argv.includes('--dialogs');
const dialogPackets = [];

const bot = mineflayer.createBot({
  host,
  port,
  username: USERNAME,
  version,
  auth: 'offline',
  hideErrors: false,
});

/** Chat lines since the last reset, as {text, commands} pairs. */
let captured = [];
bot.on('message', (message) => {
  let json = null;
  try {
    json = message.json !== undefined ? message.json : message;
  } catch (e) {
    json = null;
  }
  captured.push({ text: message.toString(), commands: runCommands(json) });
});

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Sends a command (or chat line) and returns everything printed in response. */
async function say(line, waitMs = 1200) {
  captured = [];
  bot.chat(line);
  await sleep(waitMs);
  const block = captured.slice();
  console.log(`\n> ${line}`);
  for (const entry of block) {
    console.log(`  | ${entry.text}`);
  }
  return block;
}

function allText(block) {
  return block.map((e) => e.text).join('\n');
}

function allCommands(block) {
  return block.flatMap((e) => e.commands);
}

/** The first `/vibe ui <token>` command in a block, or null. */
function firstToken(block, afterLineMatching) {
  for (const entry of block) {
    if (afterLineMatching && !afterLineMatching.test(entry.text)) {
      continue;
    }
    const ui = entry.commands.find((c) => c.startsWith('/vibe ui '));
    if (ui) {
      return ui;
    }
  }
  return null;
}

bot._client.on('packet', (data, meta) => {
  if (meta && typeof meta.name === 'string' && /dialog/i.test(meta.name)) {
    dialogPackets.push(meta.name);
  }
});

bot.once('spawn', async () => {
  console.log(`== ${USERNAME} joined ${host}:${port} (${version})`);
  try {
    await sleep(1500);

    if (expectDialogs) {
      // The dialog path: the server pushes a screen, it does not print one.
      dialogPackets.length = 0;
      const shown = await say('/vibe', 2000);
      console.log(`  dialog packets seen: ${JSON.stringify(dialogPackets)}`);
      check('bare /vibe pushed a dialog packet, not a chat block',
          dialogPackets.length > 0);
      check('bare /vibe printed no chat-rendered block',
          !/────────/.test(allText(shown)));
      dialogPackets.length = 0;
      const settings = await say('/vibe settings', 2000);
      check('/vibe settings pushed a dialog too', dialogPackets.length > 0);
      check('/vibe settings printed no chat-rendered block',
          !/────────/.test(allText(settings)));
      console.log(`\n${failures === 0 ? 'ALL BOT CHECKS PASSED' : failures + ' BOT CHECK(S) FAILED'}`);
      bot.quit();
      setTimeout(() => process.exit(failures === 0 ? 0 : 1), 500);
      return;
    }

    // ---- bare /vibe: the mod browser as a chat block ----
    let block = await say('/vibe');
    check('bare /vibe renders a titled block', /VibeMod/.test(allText(block)));
    check('bare /vibe lists the canned mod', /SmokeCanary/.test(allText(block)));
    const infoCmd = allCommands(block).find((c) => c.startsWith('/vibe info'));
    check('browser rows are clickable /vibe info commands', !!infoCmd);
    check('browser offers the admin Settings row',
        allCommands(block).some((c) => c.trim() === '/vibe settings'));

    // ---- clicking a browser row opens the hub ----
    if (infoCmd) {
      block = await say(infoCmd);
      check('the hub renders for the clicked mod', /SmokeCanary/.test(allText(block)));
      check('the hub offers the manual', allCommands(block).some((c) => /vibe manual/.test(c)));
      check('the hub offers the config form', allCommands(block).some((c) => /vibe config/.test(c)));
    }

    // ---- the settings form ----
    block = await say('/vibe settings');
    const settingsText = allText(block);
    check('settings renders the form', /settings/i.test(settingsText));
    check('settings shows a bool input with a [toggle]', /toggle/.test(settingsText));
    check('settings shows a number input with a [change]', /change/.test(settingsText));
    check('settings shows the thinking choice options', /off/.test(settingsText));
    check('settings offers Save', /Save/.test(settingsText));

    // Flip the streaming toggle and confirm the re-render shows the new value.
    // The renderer labels inputs with their human label, not their key.
    const before = /Streaming \(live progress\) = on/.test(settingsText);
    const toggleCmd = firstToken(block, /Streaming \(live progress\)/);
    check('the streaming toggle carries a /vibe ui token', !!toggleCmd);
    if (toggleCmd) {
      const after = await say(toggleCmd);
      const afterText = allText(after);
      check('toggling streaming re-renders the whole form', /settings/i.test(afterText));
      check(`streaming flipped (${before ? 'on->off' : 'off->on'})`,
          before
              ? /Streaming \(live progress\) = off/.test(afterText)
              : /Streaming \(live progress\) = on/.test(afterText));
      check('a spent token is refused the second time',
          /expired/.test(allText(await say(toggleCmd))));
    }

    // ---- the make form and a chat-captured multiline input ----
    block = await say('/vibe make');
    const makeText = allText(block);
    check('make renders the prompt form', /Make a mod/.test(makeText));
    check('make shows the multiline prompt input', /What should the mod do\?/.test(makeText));
    check('make shows the optional name hint', /Name hint \(optional\)/.test(makeText));
    const changeCmd = firstToken(block, /What should the mod do\?/);
    check('the prompt input carries a /vibe ui change token', !!changeCmd);
    if (changeCmd) {
      const prompted = await say(changeCmd);
      check('clicking change starts a chat capture',
          /type|Type/.test(allText(prompted)));
      captured = [];
      bot.chat('a mod that rains cats');
      await sleep(600);
      bot.chat('done');
      await sleep(1200);
      const filled = captured.slice();
      console.log('\n> (typed: "a mod that rains cats" / "done")');
      for (const entry of filled) {
        console.log(`  | ${entry.text}`);
      }
      check('the captured lines land as the pending value and the form re-renders',
          /rains cats/.test(allText(filled)));
      check('the captured chat line was swallowed, not broadcast',
          !/<SmokeBot>/.test(allText(filled)));
    }

    // ---- the config form ----
    block = await say('/vibe config SmokeCanary');
    check('config renders both knobs',
        /greeting/.test(allText(block)) && /count/.test(allText(block)));

    // ---- a read-only viewer ----
    block = await say('/vibe manual SmokeCanary');
    check('the manual renders its markdown', /canary/i.test(allText(block)));
  } catch (e) {
    console.log(`  FAIL: bot threw ${e && e.stack ? e.stack : e}`);
    failures++;
  } finally {
    console.log(`\n${failures === 0 ? 'ALL BOT CHECKS PASSED' : failures + ' BOT CHECK(S) FAILED'}`);
    bot.quit();
    setTimeout(() => process.exit(failures === 0 ? 0 : 1), 500);
  }
});

bot.on('error', (e) => {
  console.log(`!! bot error: ${e && e.message ? e.message : e}`);
  process.exit(1);
});

bot.on('kicked', (reason) => {
  console.log(`!! bot kicked: ${JSON.stringify(reason)}`);
  process.exit(1);
});
