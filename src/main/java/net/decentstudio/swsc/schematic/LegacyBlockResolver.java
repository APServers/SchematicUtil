package net.decentstudio.swsc.schematic;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;

import java.util.*;

/**
 * Resolves a Sponge/WorldEdit .schem blockstate string (post-1.12.2 "flattened" naming,
 * e.g. "minecraft:note_block[note=6,instrument=harp,powered=false]") to a live 1.12.2
 * Forge block registry entry.
 * <p>
 * Strategy, in order:
 *  1. Exact registry-name match, then push every parsed property onto the resulting
 *     state by NAME (not by a hand-typed table) — this alone covers the large majority
 *     of blocks whose registry name didn't change across the 1.13 flattening (rails,
 *     note blocks, doors, stairs, buttons, fences, redstone components, ...).
 *  2. A small set of finite family tables for blocks that either changed name only,
 *     split into one-block-per-variant (colors, wood types, slabs, double plants,
 *     lit/unlit pairs), or moved their data into a tile entity (potted plants, skulls,
 *     banners).
 *  3. Anything left over is reported to the caller-supplied log and replaced with air —
 *     never silently.
 */
public final class LegacyBlockResolver {

    private LegacyBlockResolver() {}

    public static final class Resolved {
        public final IBlockState state;
        /** Extra fields to merge into this voxel's tile entity NBT (creating one with "id" set if none exists). May be null. */
        public final NBTTagCompound extraTeFields;

        Resolved(IBlockState state, NBTTagCompound extraTeFields) {
            this.state = state;
            this.extraTeFields = extraTeFields;
        }
    }

    private static final String[] WOOD_TYPES = {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak"};
    private static final String[] WOOD_TYPES_LOG2 = {"acacia", "dark_oak"};

    private static final String[] COLORS = {
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink",
            "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"
    };

    /** newName-suffix (without leading underscore) -> legacy block registry name, meta = color index above. */
    private static final Map<String, String> COLOR_FAMILIES = new LinkedHashMap<>();
    static {
        COLOR_FAMILIES.put("wool", "wool");
        COLOR_FAMILIES.put("carpet", "carpet");
        COLOR_FAMILIES.put("stained_glass", "stained_glass");
        COLOR_FAMILIES.put("stained_glass_pane", "stained_glass_pane");
        COLOR_FAMILIES.put("concrete", "concrete");
        COLOR_FAMILIES.put("concrete_powder", "concrete_powder");
        COLOR_FAMILIES.put("terracotta", "stained_hardened_clay");
    }

    private static final Map<String, String> DOUBLE_PLANT_VARIANT = new LinkedHashMap<>();
    static {
        DOUBLE_PLANT_VARIANT.put("sunflower", "sunflower");
        DOUBLE_PLANT_VARIANT.put("lilac", "syringa");
        // BlockDoublePlant.EnumPlantType's real getName() for these three is "double_X", not
        // "X" — "X" is only its unlocalized name, which happens to equal the property name for
        // sunflower/syringa/paeonia but not for these. Using "X" here silently fails to match,
        // leaving the block at its default state (SUNFLOWER).
        DOUBLE_PLANT_VARIANT.put("tall_grass", "double_grass");
        DOUBLE_PLANT_VARIANT.put("large_fern", "double_fern");
        DOUBLE_PLANT_VARIANT.put("rose_bush", "double_rose");
        DOUBLE_PLANT_VARIANT.put("peony", "paeonia");
    }

    /** Exact full-name renames with no other semantic change (properties still pass through generically). */
    private static final Map<String, String> EXACT_RENAMES = new LinkedHashMap<>();
    static {
        EXACT_RENAMES.put("minecraft:grass_block", "minecraft:grass");
        EXACT_RENAMES.put("minecraft:dirt_path", "minecraft:grass_path");
        EXACT_RENAMES.put("minecraft:note_block", "minecraft:noteblock");
        EXACT_RENAMES.put("minecraft:snow_block", "minecraft:snow");
        EXACT_RENAMES.put("minecraft:snow", "minecraft:snow_layer");
        EXACT_RENAMES.put("minecraft:end_stone_bricks", "minecraft:end_bricks");
        EXACT_RENAMES.put("minecraft:cobblestone_stairs", "minecraft:stone_stairs");
        // Mossy cobblestone stairs/slabs didn't exist pre-1.13; approximate with plain cobblestone.
        EXACT_RENAMES.put("minecraft:mossy_cobblestone_stairs", "minecraft:stone_stairs");
        EXACT_RENAMES.put("minecraft:lily_pad", "minecraft:waterlily");
        EXACT_RENAMES.put("minecraft:sugar_cane", "minecraft:reeds");
        EXACT_RENAMES.put("minecraft:oak_pressure_plate", "minecraft:wooden_pressure_plate");
        EXACT_RENAMES.put("minecraft:oak_button", "minecraft:wooden_button");
        EXACT_RENAMES.put("minecraft:oak_door", "minecraft:wooden_door");
        EXACT_RENAMES.put("minecraft:terracotta", "minecraft:hardened_clay");
        // Oak was the only wood species fences/gates/trapdoors came in pre-1.13 — no species prefix.
        EXACT_RENAMES.put("minecraft:oak_fence", "minecraft:fence");
        EXACT_RENAMES.put("minecraft:oak_fence_gate", "minecraft:fence_gate");
        EXACT_RENAMES.put("minecraft:oak_trapdoor", "minecraft:trapdoor");
        EXACT_RENAMES.put("minecraft:melon", "minecraft:melon_block");
        EXACT_RENAMES.put("minecraft:nether_bricks", "minecraft:nether_brick");
        EXACT_RENAMES.put("minecraft:red_nether_bricks", "minecraft:red_nether_brick");
        EXACT_RENAMES.put("minecraft:stone_bricks", "minecraft:stonebrick");
        // "Smooth stone" as a standalone full block didn't exist before 1.14 (only as a stone
        // slab's top texture) — approximate with plain stone, its closest gameplay equivalent.
        EXACT_RENAMES.put("minecraft:smooth_stone", "minecraft:stone");
    }

    /** Stone brick finishes, which are meta values on "minecraft:stonebrick" in 1.12.2. */
    private static final Map<String, Integer> STONE_BRICK_VARIANT_META = new LinkedHashMap<>();
    static {
        STONE_BRICK_VARIANT_META.put("mossy_stone_bricks", 1);
        STONE_BRICK_VARIANT_META.put("cracked_stone_bricks", 2);
        STONE_BRICK_VARIANT_META.put("chiseled_stone_bricks", 3);
    }

    /** Dirt finishes, which are meta values on "minecraft:dirt" in 1.12.2. */
    private static final Map<String, Integer> DIRT_VARIANT_META = new LinkedHashMap<>();
    static {
        DIRT_VARIANT_META.put("coarse_dirt", 1);
        DIRT_VARIANT_META.put("podzol", 2);
    }

    /** stone's polished/rock variants, which are meta values on "minecraft:stone" in 1.12.2, not separate blocks. */
    private static final Map<String, Integer> STONE_VARIANT_META = new LinkedHashMap<>();
    static {
        STONE_VARIANT_META.put("granite", 1);
        STONE_VARIANT_META.put("polished_granite", 2);
        STONE_VARIANT_META.put("diorite", 3);
        STONE_VARIANT_META.put("polished_diorite", 4);
        STONE_VARIANT_META.put("andesite", 5);
        STONE_VARIANT_META.put("polished_andesite", 6);
    }

    /** Sandstone/red sandstone finishes, which are meta values on their base block in 1.12.2. */
    private static final Map<String, Integer> SANDSTONE_VARIANT_META = new LinkedHashMap<>();
    static {
        SANDSTONE_VARIANT_META.put("chiseled_sandstone", 1);
        SANDSTONE_VARIANT_META.put("smooth_sandstone", 2);
    }
    private static final Map<String, Integer> RED_SANDSTONE_VARIANT_META = new LinkedHashMap<>();
    static {
        RED_SANDSTONE_VARIANT_META.put("chiseled_red_sandstone", 1);
        RED_SANDSTONE_VARIANT_META.put("smooth_red_sandstone", 2);
    }

    /** Single-height grass/fern, which are meta values on "minecraft:tallgrass" in 1.12.2. */
    private static final Map<String, Integer> TALLGRASS_META = new LinkedHashMap<>();
    static {
        TALLGRASS_META.put("short_grass", 1);
        TALLGRASS_META.put("fern", 2);
    }

    /** 1.12.2 spells this color "silver"; 1.13+ renamed it "light_gray" everywhere, including glazed terracotta block names. */
    private static final Map<String, String> COLOR_NAME_1_12 = new LinkedHashMap<>();
    static {
        COLOR_NAME_1_12.put("light_gray", "silver");
    }

    /** "Item"/"Data" for a potted plant's contents, keyed by the "potted_" suffix. */
    private static final Map<String, Object[]> POTTED_CONTENTS = new LinkedHashMap<>();
    static {
        POTTED_CONTENTS.put("oak_sapling",       new Object[]{"minecraft:sapling", 0});
        POTTED_CONTENTS.put("spruce_sapling",    new Object[]{"minecraft:sapling", 1});
        POTTED_CONTENTS.put("birch_sapling",     new Object[]{"minecraft:sapling", 2});
        POTTED_CONTENTS.put("jungle_sapling",    new Object[]{"minecraft:sapling", 3});
        POTTED_CONTENTS.put("acacia_sapling",    new Object[]{"minecraft:sapling", 4});
        POTTED_CONTENTS.put("dark_oak_sapling",  new Object[]{"minecraft:sapling", 5});
        POTTED_CONTENTS.put("fern",              new Object[]{"minecraft:tallgrass", 2});
        POTTED_CONTENTS.put("dandelion",         new Object[]{"minecraft:yellow_flower", 0});
        POTTED_CONTENTS.put("poppy",             new Object[]{"minecraft:red_flower", 0});
        POTTED_CONTENTS.put("blue_orchid",       new Object[]{"minecraft:red_flower", 1});
        POTTED_CONTENTS.put("allium",            new Object[]{"minecraft:red_flower", 2});
        POTTED_CONTENTS.put("azure_bluet",       new Object[]{"minecraft:red_flower", 3});
        POTTED_CONTENTS.put("red_tulip",         new Object[]{"minecraft:red_flower", 4});
        POTTED_CONTENTS.put("orange_tulip",      new Object[]{"minecraft:red_flower", 5});
        POTTED_CONTENTS.put("white_tulip",       new Object[]{"minecraft:red_flower", 6});
        POTTED_CONTENTS.put("pink_tulip",        new Object[]{"minecraft:red_flower", 7});
        POTTED_CONTENTS.put("oxeye_daisy",       new Object[]{"minecraft:red_flower", 8});
        POTTED_CONTENTS.put("red_mushroom",      new Object[]{"minecraft:red_mushroom", 0});
        POTTED_CONTENTS.put("brown_mushroom",    new Object[]{"minecraft:brown_mushroom", 0});
        POTTED_CONTENTS.put("dead_bush",         new Object[]{"minecraft:deadbush", 0});
        POTTED_CONTENTS.put("cactus",            new Object[]{"minecraft:cactus", 0});
    }

    /** Skull/head family prefix (without _wall) -> legacy SkullType byte. */
    private static final Map<String, Integer> SKULL_TYPES = new LinkedHashMap<>();
    static {
        SKULL_TYPES.put("skeleton_skull", 0);
        SKULL_TYPES.put("wither_skeleton_skull", 1);
        SKULL_TYPES.put("zombie_head", 2);
        SKULL_TYPES.put("player_head", 3);
        SKULL_TYPES.put("creeper_head", 4);
        SKULL_TYPES.put("dragon_head", 5);
    }

    /** Standalone (non-potted) flower blocks split out of red_flower/yellow_flower in 1.13+. */
    private static final Map<String, Object[]> FLOWER_META = new LinkedHashMap<>();
    static {
        FLOWER_META.put("dandelion",    new Object[]{"minecraft:yellow_flower", 0});
        FLOWER_META.put("poppy",        new Object[]{"minecraft:red_flower", 0});
        FLOWER_META.put("blue_orchid",  new Object[]{"minecraft:red_flower", 1});
        FLOWER_META.put("allium",       new Object[]{"minecraft:red_flower", 2});
        FLOWER_META.put("azure_bluet",  new Object[]{"minecraft:red_flower", 3});
        FLOWER_META.put("red_tulip",    new Object[]{"minecraft:red_flower", 4});
        FLOWER_META.put("orange_tulip", new Object[]{"minecraft:red_flower", 5});
        FLOWER_META.put("white_tulip",  new Object[]{"minecraft:red_flower", 6});
        FLOWER_META.put("pink_tulip",   new Object[]{"minecraft:red_flower", 7});
        FLOWER_META.put("oxeye_daisy",  new Object[]{"minecraft:red_flower", 8});
    }

    /** Slab material family (non-wood): newName-without-"_slab" -> [stoneSlabMeta, "1"|"2" for stone_slab vs stone_slab2]. */
    private static final Map<String, int[]> STONE_SLAB_META = new LinkedHashMap<>();
    static {
        STONE_SLAB_META.put("smooth_stone",   new int[]{0, 1});
        STONE_SLAB_META.put("sandstone",      new int[]{1, 1});
        STONE_SLAB_META.put("petrified_oak",  new int[]{2, 1});
        STONE_SLAB_META.put("cobblestone",    new int[]{3, 1});
        STONE_SLAB_META.put("brick",          new int[]{4, 1});
        STONE_SLAB_META.put("stone_brick",    new int[]{5, 1});
        STONE_SLAB_META.put("nether_brick",   new int[]{6, 1});
        STONE_SLAB_META.put("quartz",         new int[]{7, 1});
        STONE_SLAB_META.put("red_sandstone",  new int[]{0, 2});
        // Mossy cobblestone slabs didn't exist pre-1.13; approximate with plain cobblestone.
        STONE_SLAB_META.put("mossy_cobblestone", new int[]{3, 1});
    }

    public static Resolved resolve(String paletteKey, Set<String> unresolvedLog) {
        int bracket = paletteKey.indexOf('[');
        String name = bracket >= 0 ? paletteKey.substring(0, bracket) : paletteKey;
        Map<String, String> props = parseProps(bracket >= 0 ? paletteKey.substring(bracket) : "");

        if (name.equals("minecraft:air")) {
            return new Resolved(Blocks.AIR.getDefaultState(), null);
        }

        Resolved special = resolveSpecial(name, props);
        if (special != null) return special;

        String targetName = EXACT_RENAMES.getOrDefault(name, name);
        Block block = Block.getBlockFromName(targetName);
        if (block != null && block != Blocks.AIR) {
            return new Resolved(applyPropsGenerically(block.getDefaultState(), props), null);
        }

        unresolvedLog.add(paletteKey);
        return new Resolved(Blocks.AIR.getDefaultState(), null);
    }

    private static Resolved resolveSpecial(String name, Map<String, String> props) {
        String shortName = name.startsWith("minecraft:") ? name.substring("minecraft:".length()) : name;

        Integer stoneMeta = STONE_VARIANT_META.get(shortName);
        if (stoneMeta != null) return exact("minecraft:stone", props, stoneMeta);

        Integer sandstoneMeta = SANDSTONE_VARIANT_META.get(shortName);
        if (sandstoneMeta != null) return exact("minecraft:sandstone", props, sandstoneMeta);

        Integer redSandstoneMeta = RED_SANDSTONE_VARIANT_META.get(shortName);
        if (redSandstoneMeta != null) return exact("minecraft:red_sandstone", props, redSandstoneMeta);

        Integer tallgrassMeta = TALLGRASS_META.get(shortName);
        if (tallgrassMeta != null) return exact("minecraft:tallgrass", props, tallgrassMeta);

        Integer stoneBrickMeta = STONE_BRICK_VARIANT_META.get(shortName);
        if (stoneBrickMeta != null) return exact("minecraft:stonebrick", props, stoneBrickMeta);

        Integer dirtMeta = DIRT_VARIANT_META.get(shortName);
        if (dirtMeta != null) return exact("minecraft:dirt", props, dirtMeta);

        if (shortName.equals("comparator")) {
            boolean powered = "true".equals(props.get("powered"));
            Block block = Block.getBlockFromName(powered ? "minecraft:powered_comparator" : "minecraft:unpowered_comparator");
            if (block != null) return new Resolved(applyPropsGenerically(block.getDefaultState(), props), null);
        }

        if (shortName.equals("mushroom_stem")) {
            // Both mushroom colors share one "bark" texture for the stem, carried by the
            // brown_mushroom_block registry entry regardless of which color grew it.
            int meta = allFacesTrue(props) ? 15 /* ALL_STEM */ : 10 /* STEM */;
            return exact("minecraft:brown_mushroom_block", props, meta);
        }
        if (shortName.equals("brown_mushroom_block") || shortName.equals("red_mushroom_block")) {
            return exact("minecraft:" + shortName, props, mushroomCapMeta(props));
        }

        // Signs lost their wood-type storage entirely in 1.12.2 (only one sign block exists,
        // no variant property) — orientation still carries over, species doesn't.
        if (shortName.endsWith("_wall_sign")) {
            Block wallSign = Block.getBlockFromName("minecraft:wall_sign");
            if (wallSign != null) return new Resolved(applyPropsGenerically(wallSign.getDefaultState(), props), null);
        }
        if (shortName.endsWith("_sign")) {
            Block standingSign = Block.getBlockFromName("minecraft:standing_sign");
            if (standingSign != null) return new Resolved(applyPropsGenerically(standingSign.getDefaultState(), props), null);
        }

        if (shortName.endsWith("_glazed_terracotta")) {
            String color = shortName.substring(0, shortName.length() - "_glazed_terracotta".length());
            String legacyColor = COLOR_NAME_1_12.getOrDefault(color, color);
            Block glazed = Block.getBlockFromName("minecraft:" + legacyColor + "_glazed_terracotta");
            if (glazed != null) return new Resolved(applyPropsGenerically(glazed.getDefaultState(), props), null);
        }

        if (shortName.startsWith("potted_")) {
            Object[] contents = POTTED_CONTENTS.get(shortName.substring("potted_".length()));
            Block potBlock = Block.getBlockFromName("minecraft:flower_pot");
            if (contents != null && potBlock != null) {
                NBTTagCompound te = new NBTTagCompound();
                te.setString("id", "minecraft:flower_pot");
                te.setString("Item", (String) contents[0]);
                te.setInteger("Data", (Integer) contents[1]);
                return new Resolved(potBlock.getDefaultState(), te);
            }
            // Empty pot or a plant with no 1.12 equivalent (e.g. potted_bamboo) — bare pot, no contents.
            if (potBlock != null) return new Resolved(potBlock.getDefaultState(), null);
        }

        for (Map.Entry<String, Integer> e : SKULL_TYPES.entrySet()) {
            String family = e.getKey();
            String wallName = family.contains("_skull")
                    ? family.replace("_skull", "_wall_skull")
                    : family.replace("_head", "_wall_head");
            boolean wall = shortName.equals(wallName);
            if (shortName.equals(family) || wall) {
                Block skullBlock = Block.getBlockFromName("minecraft:skull");
                if (skullBlock == null) break;
                IBlockState state = skullBlock.getDefaultState();
                NBTTagCompound te = new NBTTagCompound();
                te.setString("id", "minecraft:skull");
                te.setByte("SkullType", e.getValue().byteValue());
                if (wall && props.containsKey("facing")) {
                    state = setPropertyByName(state, "facing", props.get("facing"));
                } else {
                    state = setPropertyByName(state, "facing", "up");
                    if (props.containsKey("rotation")) {
                        try { te.setByte("Rot", (byte) Integer.parseInt(props.get("rotation"))); }
                        catch (NumberFormatException ignored) {}
                    }
                }
                return new Resolved(state, te);
            }
        }

        if (shortName.endsWith("_banner")) {
            boolean wall = shortName.endsWith("_wall_banner");
            String color = shortName.substring(0, shortName.length() - (wall ? "_wall_banner".length() : "_banner".length()));
            int colorIdx = indexOf(COLORS, color);
            Block bannerBlock = Block.getBlockFromName(wall ? "minecraft:wall_banner" : "minecraft:standing_banner");
            if (bannerBlock != null) {
                IBlockState state = applyPropsGenerically(bannerBlock.getDefaultState(), props);
                NBTTagCompound te = new NBTTagCompound();
                te.setString("id", "minecraft:banner");
                te.setInteger("Base", colorIdx >= 0 ? (15 - colorIdx) : 0);
                return new Resolved(state, te);
            }
        }

        if (shortName.endsWith("_bed")) {
            Block bedBlock = Block.getBlockFromName("minecraft:bed");
            if (bedBlock != null) return new Resolved(applyPropsGenerically(bedBlock.getDefaultState(), props), null);
        }

        for (Map.Entry<String, String> e : COLOR_FAMILIES.entrySet()) {
            String suffix = "_" + e.getKey();
            if (shortName.endsWith(suffix)) {
                String color = shortName.substring(0, shortName.length() - suffix.length());
                int colorIdx = indexOf(COLORS, color);
                if (colorIdx >= 0) {
                    Block target = Block.getBlockFromName("minecraft:" + e.getValue());
                    if (target != null) {
                        try {
                            IBlockState state = target.getStateFromMeta(colorIdx);
                            return new Resolved(applyPropsGenerically(state, props), null);
                        } catch (Exception ignored) {}
                    }
                }
            }
        }

        Object[] flower = FLOWER_META.get(shortName);
        if (flower != null) return exact((String) flower[0], props, (Integer) flower[1]);

        Resolved plant = DOUBLE_PLANT_VARIANT.containsKey(shortName)
                ? buildVariantBlock("minecraft:double_plant", "variant", DOUBLE_PLANT_VARIANT.get(shortName), props)
                : null;
        if (plant != null) return plant;

        if (shortName.equals("furnace") || shortName.equals("redstone_lamp")) {
            boolean lit = "true".equals(props.get("lit"));
            Map<String, String> rest = new LinkedHashMap<>(props);
            rest.remove("lit");
            String target = shortName.equals("furnace")
                    ? (lit ? "minecraft:lit_furnace" : "minecraft:furnace")
                    : (lit ? "minecraft:lit_redstone_lamp" : "minecraft:redstone_lamp");
            Block block = Block.getBlockFromName(target);
            if (block != null) return new Resolved(applyPropsGenerically(block.getDefaultState(), rest), null);
        }

        for (String wood : WOOD_TYPES) {
            Resolved r = tryWoodFamily(shortName, wood, props);
            if (r != null) return r;
        }

        Resolved slab = tryStoneSlab(shortName, props);
        if (slab != null) return slab;

        Resolved woodSlab = tryWoodSlab(shortName, props);
        if (woodSlab != null) return woodSlab;

        return null;
    }

    private static boolean isTrue(Map<String, String> props, String face) {
        return "true".equals(props.get(face));
    }

    private static boolean allFacesTrue(Map<String, String> props) {
        return isTrue(props, "up") && isTrue(props, "down") && isTrue(props, "north")
                && isTrue(props, "south") && isTrue(props, "east") && isTrue(props, "west");
    }

    /**
     * Translates the six per-face cap/pore booleans (1.13+) into BlockHugeMushroom's single
     * "variant" meta (1.12.2): 0 = pores on every face, 14 = cap on every face, 1-9 = a
     * north/south x east/west corner-or-edge cap position (always with up=true, down=false).
     * Anything that doesn't fit that shape falls back to the nearest of those.
     */
    private static int mushroomCapMeta(Map<String, String> props) {
        boolean up = isTrue(props, "up"), down = isTrue(props, "down");
        boolean n = isTrue(props, "north"), s = isTrue(props, "south");
        boolean e = isTrue(props, "east"), w = isTrue(props, "west");

        if (up && down && n && s && e && w) return 14; // ALL_OUTSIDE
        if (!up && !down && !n && !s && !e && !w) return 0; // ALL_INSIDE
        if (!up) return n || s || e || w ? 14 : 0;

        if (n && w) return 1;
        if (n && e) return 3;
        if (s && w) return 7;
        if (s && e) return 9;
        if (n) return 2;
        if (s) return 8;
        if (w) return 4;
        if (e) return 6;
        return 5; // CENTER — cap on top only
    }

    private static Resolved tryWoodFamily(String shortName, String wood, Map<String, String> props) {
        if (shortName.equals(wood + "_planks")) return buildVariantBlock("minecraft:planks", "variant", wood, props);
        if (shortName.equals(wood + "_sapling")) return buildVariantBlock("minecraft:sapling", "type", wood, props);
        if (shortName.equals(wood + "_log")) {
            String target = indexOf(WOOD_TYPES_LOG2, wood) >= 0 ? "minecraft:log2" : "minecraft:log";
            return buildVariantBlock(target, "variant", wood, props);
        }
        if (shortName.equals(wood + "_wood")) {
            // "_wood" (bark on all 6 sides) IS a real 1.12.2 state: log with axis=none, not
            // the x/y/z orientation carried in the .schem's own "axis" property — that one
            // describes which two faces have rings, which "_wood" doesn't have at all.
            String target = indexOf(WOOD_TYPES_LOG2, wood) >= 0 ? "minecraft:log2" : "minecraft:log";
            Resolved r = buildVariantBlock(target, "variant", wood, Collections.emptyMap());
            if (r == null) return null;
            return new Resolved(setPropertyByName(r.state, "axis", "none"), null);
        }
        if (shortName.equals(wood + "_leaves")) {
            String target = indexOf(WOOD_TYPES_LOG2, wood) >= 0 ? "minecraft:leaves2" : "minecraft:leaves";
            Map<String, String> rest = new LinkedHashMap<>(props);
            rest.remove("distance");
            rest.remove("persistent");
            return buildVariantBlock(target, "variant", wood, rest);
        }
        return null;
    }

    private static Resolved tryStoneSlab(String shortName, Map<String, String> props) {
        if (!shortName.endsWith("_slab")) return null;
        String material = shortName.substring(0, shortName.length() - "_slab".length());
        int[] meta = STONE_SLAB_META.get(material);
        if (meta == null) return null;
        String base = meta[1] == 1 ? "minecraft:stone_slab" : "minecraft:stone_slab2";
        return buildSlab(base, meta[0], props);
    }

    private static Resolved tryWoodSlab(String shortName, Map<String, String> props) {
        for (String wood : WOOD_TYPES) {
            if (shortName.equals(wood + "_slab")) {
                int meta = indexOf(WOOD_TYPES, wood);
                return buildSlab("minecraft:wooden_slab", meta, props);
            }
        }
        return null;
    }

    private static Resolved buildSlab(String baseBlockName, int materialMeta, Map<String, String> props) {
        boolean isDouble = "double".equals(props.get("type"));
        String blockName = isDouble ? doubleOf(baseBlockName) : baseBlockName;
        Block block = Block.getBlockFromName(blockName);
        if (block == null) return null;
        try {
            IBlockState state = block.getStateFromMeta(materialMeta);
            if (!isDouble && props.containsKey("type")) {
                state = setPropertyByName(state, "half", "top".equals(props.get("type")) ? "top" : "bottom");
            }
            return new Resolved(state, null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String doubleOf(String baseBlockName) {
        switch (baseBlockName) {
            case "minecraft:stone_slab": return "minecraft:double_stone_slab";
            case "minecraft:stone_slab2": return "minecraft:double_stone_slab2";
            case "minecraft:wooden_slab": return "minecraft:double_wooden_slab";
            default: return baseBlockName;
        }
    }

    private static Resolved buildVariantBlock(String blockName, String propName, String value, Map<String, String> props) {
        Block block = Block.getBlockFromName(blockName);
        if (block == null) return null;
        IBlockState state = setPropertyByName(block.getDefaultState(), propName, value);
        state = applyPropsGenerically(state, props);
        return new Resolved(state, null);
    }

    private static Resolved exact(String blockName, Map<String, String> props, int fallbackMeta) {
        Block block = Block.getBlockFromName(blockName);
        if (block == null) return new Resolved(Blocks.AIR.getDefaultState(), null);
        IBlockState state;
        try { state = block.getStateFromMeta(fallbackMeta); }
        catch (Exception e) { state = block.getDefaultState(); }
        return new Resolved(applyPropsGenerically(state, props), null);
    }

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(v)) return i;
        return -1;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static IBlockState setPropertyByName(IBlockState state, String propName, String value) {
        for (net.minecraft.block.properties.IProperty<?> prop : state.getPropertyKeys()) {
            if (prop.getName().equals(propName)) {
                return applyOne(state, (net.minecraft.block.properties.IProperty) prop, value);
            }
        }
        return state;
    }

    private static IBlockState applyPropsGenerically(IBlockState state, Map<String, String> props) {
        for (Map.Entry<String, String> e : props.entrySet()) {
            state = setPropertyByName(state, e.getKey(), e.getValue());
        }
        return state;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> IBlockState applyOne(IBlockState state, net.minecraft.block.properties.IProperty<T> prop, String value) {
        com.google.common.base.Optional<T> parsed = prop.parseValue(value);
        return parsed.isPresent() ? state.withProperty(prop, parsed.get()) : state;
    }

    private static Map<String, String> parseProps(String bracketed) {
        Map<String, String> map = new LinkedHashMap<>();
        if (bracketed.length() < 2) return map;
        String inner = bracketed.substring(1, bracketed.length() - 1);
        if (inner.isEmpty()) return map;
        for (String pair : inner.split(",")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            map.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return map;
    }
}
