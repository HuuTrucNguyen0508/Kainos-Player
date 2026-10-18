const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '..');
const html = fs.readFileSync(path.join(root, 'docs/design/redesign-2026-10/direction-5-folio.html'), 'utf8');
const source = html.slice(html.indexOf('const hex2rgb='), html.indexOf('function tokensFor('));
const tints = vm.runInNewContext(source + '; TINTS');
const ids = {folio:'FOLIO',everforest:'EVERFOREST',catppuccin:'CATPPUCCIN',gruvbox:'GRUVBOX',nord:'NORD',rosepine:'ROSE_PINE',tokyonight:'TOKYO_NIGHT'};
const fields = ['paper','sheet','field','tile','hair','rule','ink','muted','accent'];
const entries = Object.entries(tints).map(([id,tint]) => {
  const modes = ['light','dark'].map(mode => {
    const v = tint[mode];
    const args = fields.map(k => `Color(0xFF${v[k].slice(1)})`).join(', ');
    const hues = v.h.split(' ').map(h => `Color(0xFF${h.slice(1)})`).join(', ');
    return `        RawFolio(${args}, AccentRamp(${hues}))`;
  });
  return `    FolioTint.${ids[id]} to Pair(\n${modes.join(',\n')},\n    )`;
});
fs.writeFileSync(path.join(root, 'shared/src/commonMain/kotlin/com/universalmusic/player/ui/theme/FolioTints.kt'),
`package com.universalmusic.player.ui.theme

import androidx.compose.ui.graphics.Color
import com.universalmusic.player.data.settings.FolioTint

// Generated from the approved mockup by scripts/generate-folio-tints.cjs.
internal data class RawFolio(
    val paper: Color, val sheet: Color, val field: Color, val tile: Color,
    val hair: Color, val rule: Color, val ink: Color, val muted: Color,
    val accent: Color, val hues: AccentRamp,
)

internal val folioTints = mapOf(
${entries.join(',\n')},
)
`);
