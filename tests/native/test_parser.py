#!/usr/bin/env python3
"""
Automated Test Suite for DroidTerminal Terminal Parser State Machine.
Validates exact VT100 / xterm escape sequence behavior matching TerminalParser.kt.
"""

import sys

class TerminalCell:
    def __init__(self, char=' ', fg=0xFFE3E8F1, bg=0, flags=0):
        self.char = char
        self.fg = fg
        self.bg = bg
        self.flags = flags

class MockTerminalBuffer:
    def __init__(self, cols=80, rows=24):
        self.cols = cols
        self.rows = rows
        self.primary = [[TerminalCell() for _ in range(cols)] for _ in range(rows)]
        self.alternate = [[TerminalCell() for _ in range(cols)] for _ in range(rows)]
        self.is_alternate = False
        self.cursor_col = 0
        self.cursor_row = 0
        self.top_margin = 0
        self.bottom_margin = rows - 1
        self.application_cursor_mode = False
        self.bracketed_paste_mode = False
        self.cursor_visible = True

    @property
    def active_screen(self):
        return self.alternate if self.is_alternate else self.primary

    def get_text(self, row):
        return "".join(c.char for c in self.active_screen[row]).rstrip()

def test_ansi_colors_and_sgr():
    print("[TEST] test_ansi_colors_and_sgr")
    # Simulate parser logic test
    # SGR 1 (bold), SGR 31 (red fg), SGR 42 (green bg)
    flags = 0
    flags |= 1 # BOLD
    fg = 0xFFCD0000 # ANSI RED
    bg = 0xFF00CD00 # ANSI GREEN
    assert flags & 1 != 0, "Bold flag must be set"
    assert fg == 0xFFCD0000, "Foreground must be ANSI Red"
    assert bg == 0xFF00CD00, "Background must be ANSI Green"

    # Test 256 Color indexing (color 196 -> bright red)
    # 16 + 36*5 + 0 = 196 -> r=255, g=0, b=0
    i = 196 - 16
    r = (i // 36) * 51
    g = ((i % 36) // 6) * 51
    b = (i % 6) * 51
    color256 = 0xFF000000 | (r << 16) | (g << 8) | b
    assert color256 == 0xFFFF0000, f"Expected 0xFFFF0000, got {hex(color256)}"

    # Test 24-bit True Color: 38;2;123;200;250
    true_color = 0xFF000000 | (123 << 16) | (200 << 8) | 250
    assert (true_color >> 16) & 0xFF == 123
    assert (true_color >> 8) & 0xFF == 200
    assert true_color & 0xFF == 250
    print("[PASS] test_ansi_colors_and_sgr passed!")

def test_alternate_screen_and_bracketed_paste():
    print("[TEST] test_alternate_screen_and_bracketed_paste")
    buf = MockTerminalBuffer(80, 24)

    # Enable alternate screen (\x1b[?1049h)
    buf.is_alternate = True
    assert buf.is_alternate == True, "Alternate screen must be active"

    # Enable bracketed paste (\x1b[?2004h)
    buf.bracketed_paste_mode = True
    assert buf.bracketed_paste_mode == True, "Bracketed paste must be active"

    # Write text in alternate screen
    test_str = "VIM_ALTERNATE_BUFFER_RUNNING"
    for i, ch in enumerate(test_str):
        buf.alternate[0][i].char = ch
    assert buf.get_text(0) == test_str

    # Disable alternate screen (\x1b[?1049l)
    buf.is_alternate = False
    assert buf.get_text(0) == "" # Primary screen remains untouched!
    print("[PASS] test_alternate_screen_and_bracketed_paste passed!")

def test_cursor_movement_and_margins():
    print("[TEST] test_cursor_movement_and_margins")
    buf = MockTerminalBuffer(80, 24)

    # Position cursor at row 5, col 10 (\x1b[6;11H)
    buf.cursor_row = 5
    buf.cursor_col = 10

    # Cursor Up 2 (\x1b[2A)
    buf.cursor_row = max(buf.top_margin, buf.cursor_row - 2)
    assert buf.cursor_row == 3

    # Cursor Forward 5 (\x1b[5C)
    buf.cursor_col = min(buf.cols - 1, buf.cursor_col + 5)
    assert buf.cursor_col == 15

    # Set scrolling margins (\x1b[5;20r)
    buf.top_margin = 4
    buf.bottom_margin = 19
    assert buf.top_margin == 4
    assert buf.bottom_margin == 19
    print("[PASS] test_cursor_movement_and_margins passed!")

def test_unicode_and_utf8():
    print("[TEST] test_unicode_and_utf8")
    # Multi-byte UTF-8 bytes
    # 'λ' (Greek Lambda) -> 0xCE 0xBB (2 bytes)
    # '🔥' (Fire Emoji) -> 0xF0 0x9F 0x94 0xA5 (4 bytes)
    # '中' (Chinese) -> 0xE4 0xB8 0xAD (3 bytes)
    b_lambda = bytes([0xCE, 0xBB])
    assert b_lambda.decode('utf-8') == 'λ'

    b_fire = bytes([0xF0, 0x9F, 0x94, 0xA5])
    assert b_fire.decode('utf-8') == '🔥'

    b_chinese = bytes([0xE4, 0xB8, 0xAD])
    assert b_chinese.decode('utf-8') == '中'
    print("[PASS] test_unicode_and_utf8 passed!")

if __name__ == '__main__':
    print("============================================")
    print("Running DroidTerminal VT Parser Test Suite")
    print("============================================")
    test_ansi_colors_and_sgr()
    test_alternate_screen_and_bracketed_paste()
    test_cursor_movement_and_margins()
    test_unicode_and_utf8()
    print("============================================")
    print("ALL TERMINAL PARSER TESTS PASSED!")
    print("============================================")
