# Vendored third-party code

## stb_truetype.h

- What: stb_truetype v1.26 by Sean Barrett, public domain (or MIT, the file's own license text at
  its end). Used by `native/modules/totk_font.h` to rasterize the game's CFF font, read from the
  player's romfs at runtime, into the module's font atlas (docs/PLAN.md T3a' B4).
- Copied unchanged from the companions repo igawa6/eden-duo-companions, commit
  `383d2d4735f3615bbfd3f300db1e51cc850516d5`, path `native/modules/third_party/stb_truetype.h`
  (the ACNH module's copy, used by its `acnh_font.cpp`).
- sha256 `ecd30b05e0dd4fea3a13c26810dd9e1992dc379049482c393d5a19e6b5090aab` (199192 bytes),
  the same as upstream `https://raw.githubusercontent.com/nothings/stb/master/stb_truetype.h`
  fetched 2026-11-02.
- Approved for vendoring by the user (docs/PLAN.md "What needs the user", item 6).
- Compiled only inside the module's single translation unit (`STBTT_STATIC`), with the warnings
  it trips switched off around the include, as `acnh_font.cpp` does.
