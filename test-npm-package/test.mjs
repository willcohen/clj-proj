import * as proj from 'proj-wasm';

async function test() {
  console.log('Testing built npm package...\n');
  
  try {
    // Initialize PROJ
    await proj.init();
    console.log('✓ PROJ initialized');
    
    // Create context
    const ctx = proj.context_create();
    console.log('✓ Context created');
    
    // Create transformer
    const transformer = proj.proj_create_crs_to_crs({
      context: ctx,
      source_crs: "EPSG:4326",
      target_crs: "EPSG:3857"
    });
    console.log('✓ Transformer created');
    
    // Transform coordinates
    const coords = proj.coord_array(1);
    // Note: For EPSG:4326, coordinates should be in [lat, lon] order
    proj.set_coords_BANG_(coords, [[42.3601, -71.0589, 0, 0]]); // Boston City Hall
    
    // Get the malloc pointer for transformation
    const malloc = coords.malloc || coords.get('malloc');
    
    proj.proj_trans_array({
      p: transformer,
      direction: 1, // PJ_FWD
      n: 1,
      coord: malloc
    });
    
    const x = coords.array[0];
    const y = coords.array[1];
    console.log(`✓ Transformed Boston: [${x.toFixed(2)}, ${y.toFixed(2)}]`);
    
    // Validate transformation - expecting Web Mercator coordinates for Boston
    if (x < -7910000 && x > -7911000 && y > 5215000 && y < 5216000) {
      console.log('✓ Transformation results are correct');
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