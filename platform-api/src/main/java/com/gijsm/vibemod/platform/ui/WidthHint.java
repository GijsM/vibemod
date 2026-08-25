package com.gijsm.vibemod.platform.ui;

/**
 * Layout hint for body blocks, inputs and buttons. The dialog renderer maps
 * these to its pixel scale (BODY=400, WIDE=600, INPUT=350, ROW=300 — the v1
 * DialogKit constants); the chat renderer ignores them.
 */
public enum WidthHint {
    BODY, WIDE, INPUT, ROW
}
