// Copyright (c) 2024, 2025, 2026 Will Cohen
//
// Part of clj-proj, under the MIT License.
// See LICENSE for license information.
// SPDX-License-Identifier: MIT

import * as proj from 'proj-wasm';

async function test() {
  console.log('Testing built npm package...\n');
  
  try {
    await proj.init();
    console.log('ok: PROJ initialized');

    // The context is auto-created.
    const transformer = await proj.projCreateCrsToCrs({
      source_crs: "EPSG:4326",
      target_crs: "EPSG:3857"
    });
    console.log('ok: Transformer created');

    const coords = await proj.coordArray(1);
    // EPSG:4326 coordinates use [lat, lon] order.
    await proj.setCoords(coords, [[42.3601, -71.0589, 0, 0]]); // Boston City Hall

    await proj.projTransArray({
      p: transformer,
      direction: proj.PJ_FWD,
      n: 1,
      coord: coords
    });

    const result = await proj.getCoords(coords, 0);
    const x = result[0];
    const y = result[1];
    console.log(`ok: Transformed Boston: [${x.toFixed(2)}, ${y.toFixed(2)}]`);

    // Web Mercator range for Boston
    if (x < -7910000 && x > -7911000 && y > 5215000 && y < 5216000) {
      console.log('ok: Transformation results are correct');
    } else {
      throw new Error(`Unexpected transformation results: [${x}, ${y}]`);
    }
    
    console.log('\nAll tests passed!');
    process.exit(0);
  } catch (error) {
    console.error('Test failed:', error);
    process.exit(1);
  }
}

test();