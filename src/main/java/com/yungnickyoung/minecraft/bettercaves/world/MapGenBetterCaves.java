package com.yungnickyoung.minecraft.bettercaves.world;

import com.yungnickyoung.minecraft.bettercaves.config.Configuration;
import com.yungnickyoung.minecraft.bettercaves.config.Settings;
import com.yungnickyoung.minecraft.bettercaves.enums.CaveFrequency;
import com.yungnickyoung.minecraft.bettercaves.enums.CaveType;
import com.yungnickyoung.minecraft.bettercaves.enums.CavernType;
import com.yungnickyoung.minecraft.bettercaves.util.BetterCavesUtil;
import com.yungnickyoung.minecraft.bettercaves.noise.FastNoise;
import com.yungnickyoung.minecraft.bettercaves.world.bedrock.FlattenBedrock;
import com.yungnickyoung.minecraft.bettercaves.world.cave.*;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkPrimer;
import net.minecraft.world.gen.MapGenCaves;

import javax.annotation.Nonnull;

/**
 * Class that overrides vanilla cave gen with Better Caves gen.
 * Combines multiple types of caves and caverns using different types of noise to create a
 * novel underground experience.
 */
public class MapGenBetterCaves extends MapGenCaves {
    // Cave types
    private AbstractBC caveCubic;
    private AbstractBC caveSimplex;

    // Cavern types
    private AbstractBC cavernLava;
    private AbstractBC cavernFloored;
    private AbstractBC cavernWater;

    private int surfaceCutoff;

    // Vanilla cave gen if user sets config to use it
    private MapGenCaves defaultCaveGen;

    // Noise generators to group caves into cave regions based on xz-coordinates.
    // Cavern Region Controller uses simplex noise while the others use Voronoi regions (cellular noise)
    private FastNoise waterCavernController;
    private FastNoise cavernRegionController;
    private FastNoise caveRegionController;

    // Region generation noise thresholds, based on user config
    private float cubicCaveThreshold;
    private float simplexCaveThreshold;
    private float lavaCavernThreshold;
    private float flooredCavernThreshold;
    private float waterRegionThreshold;

    // Dictates the degree of smoothing along cavern region boundaries
    private float transitionRange = .15f;

    // Config option for using vanilla cave gen in some areas
    private boolean enableVanillaCaves;

    // Config option for using water regions
    private boolean enableWaterRegions;

    private IBlockState lavaBlock;
    private IBlockState waterBlock;

    // DEBUG
    private AbstractBC testCave;

    public MapGenBetterCaves() {
    }

    // DEBUG - used to test new noise types/params with the TestCave type
    private void debugGenerate(World worldIn, int chunkX, int chunkZ, @Nonnull ChunkPrimer primer) {
        int maxSurfaceHeight = BetterCavesUtil.getMaxSurfaceAltitudeChunk(primer);
        int minSurfaceHeight = BetterCavesUtil.getMinSurfaceAltitudeChunk(primer);
        if (worldIn.provider.getDimension() == 0) {
            for (int localX = 0; localX < 16; localX++) {
                for (int localZ = 0; localZ < 16; localZ++) {
                    testCave.generateColumn(chunkX, chunkZ, primer, localX, localZ, 1, maxSurfaceHeight, maxSurfaceHeight, minSurfaceHeight, surfaceCutoff, Blocks.FLOWING_LAVA.getDefaultState());
                }
            }
        }
    }

    /**
     * Function for generating Better Caves in a single chunk. This overrides the vanilla cave generation, which is
     * ordinarily performed by the MapGenCaves class.
     * This function is called for every new chunk that is generated in a world.
     * @param worldIn The Minecraft world
     * @param chunkX The chunk's x-coordinate (on the chunk grid, not the block grid)
     * @param chunkZ The chunk's z-coordinate (on the chunk grid, not the block grid)
     * @param primer The chunk's ChunkPrimer
     */
    @Override
    public void generate(World worldIn, int chunkX, int chunkZ, @Nonnull ChunkPrimer primer) {
        if (world == null) { // First call - (lazy) initialization of all cave generators
            this.initialize(worldIn);
        }

        // Note - if changing dimensions, world will be the same, but world.provider.getDimensionType() will be different
//
//        if (world != worldIn) {
//            Settings.LOGGER.warn("WORLD NOT THE SAME");
//        }
//
//        Settings.LOGGER.warn("BETTERCAVESWORLD " + worldIn.provider.getDimensionType() + ": " + worldIn.provider.getDimension());


        // Use debug function for testing purposes, if debug flag is set
        if (Settings.DEBUG_WORLD_GEN) {
            debugGenerate(worldIn, chunkX, chunkZ, primer);
            return;
        }

        // Default vals for max/min surface height
        int maxSurfaceHeight = 128;
        int minSurfaceHeight = 60;

        // Cave generators - we will determine exactly what type these are based on the cave region for each column
        AbstractBC cavernGen;
        AbstractBC caveGen;

        // These values are later set to the correct cave/cavern type's config vars for
        // caveBottom, and caveTop (only applicable for caverns, since caves perform some additional
        // operations to smoothly transition into the surface)
        int cavernBottomY;
        int cavernTopY;
        int caveBottomY;

        // Only use Better Caves generation in whitelisted dimensions
        int dimensionID = worldIn.provider.getDimension();
        boolean isWhitelisted = false;

        // Ignore the dimension ID list if global whitelisting is enabled
        if (Configuration.caveSettings.enableGlobalWhitelist)
            isWhitelisted = true;

        // Check if dimension is whitelisted
        for (int dim : Configuration.caveSettings.whitelistedDimensionIDs) {
            if (dimensionID == dim) {
                isWhitelisted = true;
                break;
            }
        }

        // If not whitelisted, use default cave gen instead of Better Caves
        if (!isWhitelisted) {
            defaultCaveGen.generate(worldIn, chunkX, chunkZ, primer);
            return;
        }

        // Flatten bedrock, if enabled
        FlattenBedrock.flattenBedrock(primer);

        // We split chunks into 2x2 sub-chunks along the x-z axis for surface height calculations
        for (int subX = 0; subX < 8; subX++) {
            for (int subZ = 0; subZ < 8; subZ++) {
                if (!Configuration.debugsettings.debugVisualizer)
                    maxSurfaceHeight = BetterCavesUtil.getMaxSurfaceAltitudeSubChunk(primer, subX, subZ);

                // maxSurfaceHeight (also used for max cave altitude) cannot exceed Max Cave Altitude setting
                maxSurfaceHeight = Math.min(maxSurfaceHeight, Configuration.caveSettings.caves.maxCaveAltitude);

                for (int offsetX = 0; offsetX < 2; offsetX++) {
                    for (int offsetZ = 0; offsetZ < 2; offsetZ++) {
                        int localX = (subX * 2) + offsetX; // chunk-local x-coordinate (0-15, inclusive)
                        int localZ = (subZ * 2) + offsetZ; // chunk-local z-coordinate (0-15, inclusive)
                        int realX = (chunkX * 16) + localX;
                        int realZ = (chunkZ * 16) + localZ;

                        /* --------------------------- Configure Caves --------------------------- */

                        // Get noise values used to determine cave region
                        float caveRegionNoise = caveRegionController.GetNoise(realX, realZ);

                        /* Determine cave type for this column. We have two thresholds, one for cubic caves and one for
                         * simplex caves. Since the noise value generated for the region is between -1 and 1, we (by
                         * default) designate all negative values as cubic caves, and all positive as simplex. However,
                         * we allow the user to tweak the cutoff values based on the frequency they designate for each cave
                         * type, so we must also check for values between the two thresholds,
                         * e.g. if (cubicCaveThreshold <= noiseValue < simplexCaveThreshold).
                         * In this case, we use vanilla cave generation if it is enabled; otherwise we dig no caves
                         * out of this chunk.
                         */
                        if (caveRegionNoise < this.cubicCaveThreshold) {
                            caveGen = this.caveCubic;
                            caveBottomY = Configuration.caveSettings.caves.cubicCave.caveBottom;
                        } else if (caveRegionNoise >= this.simplexCaveThreshold) {
                            caveGen = this.caveSimplex;
                            caveBottomY = Configuration.caveSettings.caves.simplexCave.caveBottom;
                        } else {
                            if (this.enableVanillaCaves) {
                                defaultCaveGen.generate(worldIn, chunkX, chunkZ, primer);
                                return;
                            }
                            caveGen = null;
                            caveBottomY = 255;
                        }

                        /* --------------------------- Configure Caverns --------------------------- */

                        // Noise values used to determine cavern region
                        float cavernRegionNoise = cavernRegionController.GetNoise(realX, realZ);
                        float waterRegionNoise = 99;

                        // Only bother calculating noise for water region if enabled
                        if (enableWaterRegions)
                            waterRegionNoise = waterCavernController.GetNoise(realX, realZ);

                        // If water region threshold check is passed, change liquid block to water
                        IBlockState liquidBlock = lavaBlock;
                        if (waterRegionNoise < waterRegionThreshold)
                            liquidBlock = waterBlock;

                        // Determine cavern type for this column. Caverns generate at low altitudes only.
                        if (cavernRegionNoise < lavaCavernThreshold) {
                            if (this.enableWaterRegions && waterRegionNoise < this.waterRegionThreshold) {
                                // Generate water cavern in this column
                                cavernGen = this.cavernWater;
                            } else {
                                // Generate lava cavern in this column
                                cavernGen = this.cavernLava;
                            }
                            // Water caverns use the same cave top/bottom as lava caverns
                            cavernBottomY = Configuration.caveSettings.caverns.lavaCavern.caveBottom;
                            cavernTopY = Configuration.caveSettings.caverns.lavaCavern.caveTop;
                        } else if (cavernRegionNoise >= lavaCavernThreshold && cavernRegionNoise <= flooredCavernThreshold) {
                            /* Similar to determining cave type above, we must check for values between the two adjusted
                             * thresholds, i.e. lavaCavernThreshold < noiseValue <= flooredCavernThreshold.
                             * In this case, we just continue generating the caves we were generating above, instead
                             * of generating a cavern.
                             */
                            cavernGen = caveGen;
                            cavernBottomY = caveBottomY;
                            cavernTopY = caveBottomY;
                        } else {
                            // Generate floored cavern in this column
                            cavernGen = this.cavernFloored;
                            cavernBottomY = Configuration.caveSettings.caverns.flooredCavern.caveBottom;
                            cavernTopY = Configuration.caveSettings.caverns.flooredCavern.caveTop;
                        }

                        // Extra check to provide close-off transitions on cavern edges
                        if (Configuration.caveSettings.caverns.enableBoundarySmoothing) {
                            if (cavernRegionNoise >= lavaCavernThreshold && cavernRegionNoise <= lavaCavernThreshold + transitionRange) {
                                float smoothAmp = Math.abs((cavernRegionNoise - (lavaCavernThreshold + transitionRange)) / transitionRange);
                                if (this.enableWaterRegions && waterRegionNoise < this.waterRegionThreshold)
                                    this.cavernWater.generateColumn(chunkX, chunkZ, primer, localX, localZ, Configuration.caveSettings.caverns.lavaCavern.caveBottom, Configuration.caveSettings.caverns.lavaCavern.caveTop,
                                            maxSurfaceHeight, minSurfaceHeight, surfaceCutoff, liquidBlock, smoothAmp);
                                else
                                    this.cavernLava.generateColumn(chunkX, chunkZ, primer, localX, localZ, Configuration.caveSettings.caverns.lavaCavern.caveBottom, Configuration.caveSettings.caverns.lavaCavern.caveTop,
                                        maxSurfaceHeight, minSurfaceHeight, surfaceCutoff, liquidBlock, smoothAmp);
                            } else if (cavernRegionNoise <= flooredCavernThreshold && cavernRegionNoise >= flooredCavernThreshold - transitionRange) {
                                float smoothAmp = Math.abs((cavernRegionNoise - (flooredCavernThreshold - transitionRange)) / transitionRange);
                                this.cavernFloored.generateColumn(chunkX, chunkZ, primer, localX, localZ, Configuration.caveSettings.caverns.flooredCavern.caveBottom, Configuration.caveSettings.caverns.flooredCavern.caveTop,
                                        maxSurfaceHeight, minSurfaceHeight, surfaceCutoff, liquidBlock, smoothAmp);
                            }
                        }

                        /* --------------- Dig out caves and caverns for this column --------------- */
                        // Top (Cave) layer:
                        if (caveGen != null)
                            caveGen.generateColumn(chunkX, chunkZ, primer, localX, localZ, caveBottomY, maxSurfaceHeight,
                                maxSurfaceHeight, minSurfaceHeight, surfaceCutoff, liquidBlock);
                        // Bottom (Cavern) layer:
                        if (cavernGen != null)
                            cavernGen.generateColumn(chunkX, chunkZ, primer, localX, localZ, cavernBottomY, cavernTopY,
                                maxSurfaceHeight, minSurfaceHeight, surfaceCutoff, liquidBlock);

                    }
                }
            }
        }
    }

    /**
     * @return threshold value for cubic cave spawn rate based on Config setting
     */
    private float calcCubicCaveThreshold() {
        switch (Configuration.caveSettings.caves.cubicCave.caveFrequency) {
            case None:
                return -99f;
            case Rare:
                return -.6f;
            case Common:
                return -.2f;
            case Custom:
                return -1f + Configuration.caveSettings.caves.cubicCave.customFrequency;
            default: // VeryCommon
                return 0;
        }
    }

    /**
     * @return threshold value for simplex cave spawn rate based on Config setting
     */
    private float calcSimplexCaveThreshold() {
        switch (Configuration.caveSettings.caves.simplexCave.caveFrequency) {
            case None:
                return 99f;
            case Rare:
                return .6f;
            case Common:
                return .2f;
            case Custom:
                return 1f - Configuration.caveSettings.caves.simplexCave.customFrequency;
            default: // VeryCommon
                return 0;
        }
    }

    /**
     * @return threshold value for lava cavern spawn rate based on Config setting
     */
    private float calcLavaCavernThreshold() {
        switch (Configuration.caveSettings.caverns.lavaCavern.caveFrequency) {
            case None:
                return -99f;
            case Rare:
                return -.8f;
            case Common:
                return -.3f;
            case VeryCommon:
                return -.1f;
            case Custom:
                return -1f + Configuration.caveSettings.caverns.lavaCavern.customFrequency;
            default: // Normal
                return -.4f;
        }
    }

    /**
     * @return threshold value for floored cavern spawn rate based on Config setting
     */
    private float calcFlooredCavernThreshold() {
        switch (Configuration.caveSettings.caverns.flooredCavern.caveFrequency) {
            case None:
                return 99f;
            case Rare:
                return .8f;
            case Common:
                return .3f;
            case VeryCommon:
                return .1f;
            case Custom:
                return 1f - Configuration.caveSettings.caverns.flooredCavern.customFrequency;
            default: // Normal
                return .4f;
        }
    }

    /**
     * @return threshold value for water region spawn rate based on Config setting
     */
    private float calcWaterRegionThreshold() {
        switch (Configuration.caveSettings.waterRegions.waterRegionFrequency) {
            case Rare:
                return -.4f;
            case Common:
                return .1f;
            case VeryCommon:
                return .3f;
            case Always:
                return 99f;
            case Custom:
                return 2f * Configuration.caveSettings.waterRegions.customFrequency - 1;
            default: // Normal
                return -.15f;
        }
    }

    /**
     * Initialize Better Caves generators and cave region controllers for this world.
     * @param worldIn The minecraft world
     */
    private void initialize(World worldIn) {

        Settings.LOGGER.warn("BETTERCAVESWORLDINIT " + worldIn.provider.getDimensionType() + ": " + worldIn.provider.getDimension());

        // Ch

        this.world = worldIn;
        this.defaultCaveGen = new MapGenCaves();
        this.enableVanillaCaves = Configuration.caveSettings.caves.vanillaCave.enableVanillaCaves;
        this.enableWaterRegions = Configuration.caveSettings.waterRegions.enableWaterRegions;

        // Determine noise thresholds for cavern spawns based on user config
        this.lavaCavernThreshold = calcLavaCavernThreshold();
        this.flooredCavernThreshold = calcFlooredCavernThreshold();
        this.waterRegionThreshold = calcWaterRegionThreshold();

        // Determine noise thresholds for caverns based on user config
        this.cubicCaveThreshold = calcCubicCaveThreshold();
        this.simplexCaveThreshold = calcSimplexCaveThreshold();

        // Get user setting for surface cutoff depth used to close caves off towards the surface
        this.surfaceCutoff = Configuration.caveSettings.caves.surfaceCutoff;

        // Determine cave region size
        float caveRegionSize;
        switch (Configuration.caveSettings.caves.caveRegionSize) {
            case Small:
                caveRegionSize = .007f;
                break;
            case Large:
                caveRegionSize = .0032f;
                break;
            case ExtraLarge:
                caveRegionSize = .001f;
                break;
            default: // Medium
                caveRegionSize = .005f;
                break;
        }

        // Determine cavern region size, as well as jitter to make Voronoi regions more varied in shape
        float cavernRegionSize;
        float waterCavernRegionSize = .003f;
        switch (Configuration.caveSettings.caverns.cavernRegionSize) {
            case Small:
                cavernRegionSize = .01f;
                break;
            case Large:
                cavernRegionSize = .005f;
                break;
            case ExtraLarge:
                cavernRegionSize = .001f;
                waterCavernRegionSize = .0005f;
                break;
            default: // Medium
                cavernRegionSize = .007f;
                break;
        }

        // Initialize region controllers using world seed and user config option for region size
        this.caveRegionController = new FastNoise();
        this.caveRegionController.SetSeed((int)worldIn.getSeed() + 222);
        this.caveRegionController.SetFrequency(caveRegionSize);
        this.caveRegionController.SetNoiseType(FastNoise.NoiseType.Cellular);
        this.caveRegionController.SetCellularDistanceFunction(FastNoise.CellularDistanceFunction.Natural);

        // Note that Cavern Region Controller uses Simplex noise instead of Cellular
        this.cavernRegionController = new FastNoise();
        this.cavernRegionController.SetSeed((int)worldIn.getSeed() + 333);
        this.cavernRegionController.SetFrequency(cavernRegionSize);

        this.waterCavernController = new FastNoise();
        this.waterCavernController.SetSeed((int)worldIn.getSeed() + 444);
        this.waterCavernController.SetFrequency(waterCavernRegionSize);
        this.waterCavernController.SetNoiseType(FastNoise.NoiseType.Cellular);
        this.waterCavernController.SetCellularDistanceFunction(FastNoise.CellularDistanceFunction.Natural);

        // Set lava block
        try {
            lavaBlock = Block.getBlockFromName(Configuration.lavaBlock).getDefaultState();
            Settings.LOGGER.info("Using block '" + Configuration.lavaBlock + "' as lava in cave generation...");
        } catch (Exception e) {
            Settings.LOGGER.warn("Unable to use block '" + Configuration.lavaBlock + "': " + e);
            Settings.LOGGER.warn("Using vanilla lava instead...");
            lavaBlock = Blocks.FLOWING_LAVA.getDefaultState();
        }

        if (lavaBlock == null) {
            Settings.LOGGER.warn("Unable to use block '" + Configuration.lavaBlock + "': null block returned.\n Using vanilla lava instead...");
            lavaBlock = Blocks.FLOWING_LAVA.getDefaultState();
        }

        // Set water block
        try {
            waterBlock = Block.getBlockFromName(Configuration.waterblock).getDefaultState();
            Settings.LOGGER.info("Using block '" + Configuration.waterblock + "' as water in cave generation...");
        } catch (Exception e) {
            Settings.LOGGER.warn("Unable to use block '" + Configuration.waterblock + "': " + e);
            Settings.LOGGER.warn("Using vanilla water instead...");
            waterBlock = Blocks.FLOWING_WATER.getDefaultState();
        }

        if (waterBlock == null) {
            Settings.LOGGER.warn("Unable to use block '" + Configuration.waterblock + "': null block returned.\n Using vanilla water instead...");
            waterBlock = Blocks.FLOWING_WATER.getDefaultState();
        }

        /* ---------- Initialize all Better Cave generators using config options ---------- */
        this.caveCubic = new CaveBC(
                world,
                CaveType.CUBIC,
                Configuration.caveSettings.caves.cubicCave.fractalOctaves,
                Configuration.caveSettings.caves.cubicCave.fractalGain,
                Configuration.caveSettings.caves.cubicCave.fractalFrequency,
                Configuration.caveSettings.caves.cubicCave.numGenerators,
                Configuration.caveSettings.caves.cubicCave.noiseThreshold,
                Configuration.caveSettings.caves.cubicCave.turbulenceOctaves,
                Configuration.caveSettings.caves.cubicCave.turbulenceGain,
                Configuration.caveSettings.caves.cubicCave.turbulenceFrequency,
                Configuration.caveSettings.caves.cubicCave.enableTurbulence,
                Configuration.caveSettings.caves.cubicCave.yCompression,
                Configuration.caveSettings.caves.cubicCave.xzCompression,
                Configuration.caveSettings.caves.cubicCave.yAdjust,
                Configuration.caveSettings.caves.cubicCave.yAdjustF1,
                Configuration.caveSettings.caves.cubicCave.yAdjustF2,
                Blocks.PLANKS.getDefaultState()
        );

        this.caveSimplex = new CaveBC(
                world,
                CaveType.SIMPLEX,
                Configuration.caveSettings.caves.simplexCave.fractalOctaves,
                Configuration.caveSettings.caves.simplexCave.fractalGain,
                Configuration.caveSettings.caves.simplexCave.fractalFrequency,
                Configuration.caveSettings.caves.simplexCave.numGenerators,
                Configuration.caveSettings.caves.simplexCave.noiseThreshold,
                Configuration.caveSettings.caves.simplexCave.turbulenceOctaves,
                Configuration.caveSettings.caves.simplexCave.turbulenceGain,
                Configuration.caveSettings.caves.simplexCave.turbulenceFrequency,
                Configuration.caveSettings.caves.simplexCave.enableTurbulence,
                Configuration.caveSettings.caves.simplexCave.yCompression,
                Configuration.caveSettings.caves.simplexCave.xzCompression,
                Configuration.caveSettings.caves.simplexCave.yAdjust,
                Configuration.caveSettings.caves.simplexCave.yAdjustF1,
                Configuration.caveSettings.caves.simplexCave.yAdjustF2,
                Blocks.COBBLESTONE.getDefaultState()
        );

        this.cavernLava = new CavernBC(
                world,
                CavernType.LAVA,
                Configuration.caveSettings.caverns.lavaCavern.fractalOctaves,
                Configuration.caveSettings.caverns.lavaCavern.fractalGain,
                Configuration.caveSettings.caverns.lavaCavern.fractalFrequency,
                Configuration.caveSettings.caverns.lavaCavern.numGenerators,
                Configuration.caveSettings.caverns.lavaCavern.noiseThreshold,
                Configuration.caveSettings.caverns.lavaCavern.yCompression,
                Configuration.caveSettings.caverns.lavaCavern.xzCompression,
                Blocks.REDSTONE_BLOCK.getDefaultState()
        );

        this.cavernFloored = new CavernBC(
                world,
                CavernType.FLOORED,
                Configuration.caveSettings.caverns.flooredCavern.fractalOctaves,
                Configuration.caveSettings.caverns.flooredCavern.fractalGain,
                Configuration.caveSettings.caverns.flooredCavern.fractalFrequency,
                Configuration.caveSettings.caverns.flooredCavern.numGenerators,
                Configuration.caveSettings.caverns.flooredCavern.noiseThreshold,
                Configuration.caveSettings.caverns.flooredCavern.yCompression,
                Configuration.caveSettings.caverns.flooredCavern.xzCompression,
                Blocks.GOLD_BLOCK.getDefaultState()
        );

        this.cavernWater = new CavernBC(
                world,
                CavernType.WATER,
                Configuration.caveSettings.waterRegions.waterCavern.fractalOctaves,
                Configuration.caveSettings.waterRegions.waterCavern.fractalGain,
                Configuration.caveSettings.waterRegions.waterCavern.fractalFrequency,
                Configuration.caveSettings.waterRegions.waterCavern.numGenerators,
                Configuration.caveSettings.waterRegions.waterCavern.noiseThreshold,
                Configuration.caveSettings.waterRegions.waterCavern.yCompression,
                Configuration.caveSettings.waterRegions.waterCavern.xzCompression,
                Blocks.LAPIS_BLOCK.getDefaultState()
        );

        this.testCave = new TestCave(
                world,
                Configuration.testSettings.fractalOctaves,
                Configuration.testSettings.fractalGain,
                Configuration.testSettings.fractalFrequency,
                Configuration.testSettings.numGenerators,
                Configuration.testSettings.noiseThreshold,
                Configuration.testSettings.turbulenceOctaves,
                Configuration.testSettings.turbulenceGain,
                Configuration.testSettings.turbulenceFrequency,
                Configuration.testSettings.enableTurbulence,
                Configuration.testSettings.yCompression,
                Configuration.testSettings.xzCompression,
                Configuration.testSettings.yAdjust,
                Configuration.testSettings.yAdjustF1,
                Configuration.testSettings.yAdjustF2
        );
    }
}
