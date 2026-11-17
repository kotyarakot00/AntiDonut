package com.example.addon.modules;

import com.example.addon.AntiDonut;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.phys.AABB;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SeedOreESP extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    // Список разрешенных натурально генерируемых руд
    private static final Set<Block> ALLOWED_ORES = Set.of(
        Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE,
        Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE,
        Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE,
        Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.NETHER_GOLD_ORE,
        Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE,
        Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE,
        Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE,
        Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE,
        Blocks.NETHER_QUARTZ_ORE, Blocks.ANCIENT_DEBRIS
    );

    private final Setting<String> seedInput = sgGeneral.add(new StringSetting.Builder()
        .name("world-seed")
        .description("Сид сервера (число). Например: -4172144997902289642")
        .defaultValue("0")
        .onChanged(s -> resetCache())
        .build()
    );

    private final Setting<List<Block>> blocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("blocks")
        .description("Выберите руды для поиска по сиду.")
        .defaultValue(Blocks.DEEPSLATE_COAL_ORE, Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.ANCIENT_DEBRIS)
        .filter(ALLOWED_ORES::contains)
        .onChanged(b -> resetCache())
        .build()
    );

    private final Setting<Integer> chunkRadius = sgGeneral.add(new IntSetting.Builder()
        .name("chunk-radius")
        .description("Радиус сканирования чанков вокруг игрока.")
        .defaultValue(4)
        .min(1)
        .sliderMax(10)
        .onChanged(r -> resetCache())
        .build()
    );

    private final Setting<Boolean> hideAir = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-mined")
        .description("Скрывать подсветку, если блок на этих координатах уже выкопан.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("Режим отрисовки бокса.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Рисовать линии от прицела до найденных руд.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> defaultColor = sgRender.add(new ColorSetting.Builder()
        .name("default-color")
        .description("Базовый цвет для руд.")
        .defaultValue(new SettingColor(0, 255, 170, 255))
        .build()
    );

    private final Map<BlockPos, Block> foundOres = new ConcurrentHashMap<>();
    private final Set<ChunkPos> scannedChunks = ConcurrentHashMap.newKeySet();

    public SeedOreESP() {
        super(AntiDonut.CATEGORY, "seed-ore-esp", "Подсвечивает выбранные руды по сиду мира в обход Anti-Xray.");
    }

    @Override
    public void onActivate() {
        resetCache();
    }

    @Override
    public void onDeactivate() {
        resetCache();
    }

    private void resetCache() {
        foundOres.clear();
        scannedChunks.clear();
    }

    private long parseSeed() {
        try {
            return Long.parseLong(seedInput.get().trim());
        } catch (NumberFormatException e) {
            return seedInput.get().trim().hashCode();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        long worldSeed = parseSeed();
        if (worldSeed == 0 || blocks.get().isEmpty()) return;

        ChunkPos playerChunk = mc.player.chunkPosition();
        int r = chunkRadius.get();

        for (int cx = playerChunk.x - r; cx <= playerChunk.x + r; cx++) {
            for (int cz = playerChunk.z - r; cz <= playerChunk.z + r; cz++) {
                ChunkPos cp = new ChunkPos(cx, cz);
                if (!scannedChunks.contains(cp) && mc.level.hasChunk(cx, cz)) {
                    scannedChunks.add(cp);
                    simulateChunkOres(worldSeed, cp);
                }
            }
        }
    }

    private void simulateChunkOres(long worldSeed, ChunkPos cp) {
        List<Block> selected = blocks.get();

        for (Block targetBlock : selected) {
            OreConfig cfg = getOreConfig(targetBlock);
            if (cfg == null) continue;

            // Уникальный сид для конкретного чанка и типа руды
            long salt = Block.getId(targetBlock.defaultBlockState()) * 341873128712L;
            RandomSource random = new XoroshiroRandomSource(
                (cp.getMinBlockX() * 341873128712L) ^ (cp.getMinBlockZ() * 132897987541L) ^ worldSeed ^ salt
            );

            for (int attempt = 0; attempt < cfg.attempts; attempt++) {
                int x = cp.getMinBlockX() + random.nextInt(16);
                int z = cp.getMinBlockZ() + random.nextInt(16);
                int y = cfg.triangular
                    ? cfg.minY + random.nextInt((cfg.maxY - cfg.minY) / 2 + 1) + random.nextInt((cfg.maxY - cfg.minY) / 2 + 1)
                    : cfg.minY + random.nextInt(Math.max(1, cfg.maxY - cfg.minY + 1));

                // Проверка соответствия высоте (глубинный сланец <= 0, обычный камень > 0)
                if (cfg.onlyDeepslate && y > 0) continue;
                if (cfg.onlyStone && y <= 0) continue;

                for (int i = 0; i < cfg.veinSize; i++) {
                    int ox = x + random.nextInt(3) - 1;
                    int oy = y + random.nextInt(3) - 1;
                    int oz = z + random.nextInt(3) - 1;

                    if (cfg.onlyDeepslate && oy > 0) continue;
                    if (cfg.onlyStone && oy <= 0) continue;

                    BlockPos pos = new BlockPos(ox, oy, oz);
                    if (!mc.level.getBlockState(pos).isAir() && mc.level.getBlockState(pos).getFluidState().isEmpty()) {
                        foundOres.put(pos, targetBlock);
                    }
                }
            }
        }
    }

    private OreConfig getOreConfig(Block block) {
        if (block == Blocks.DEEPSLATE_COAL_ORE) return new OreConfig(-8, 0, 20, 17, false, true, false);
        if (block == Blocks.COAL_ORE) return new OreConfig(1, 192, 20, 17, true, false, true);
        if (block == Blocks.DEEPSLATE_DIAMOND_ORE) return new OreConfig(-64, 0, 7, 8, true, true, false);
        if (block == Blocks.DIAMOND_ORE) return new OreConfig(1, 16, 7, 8, true, false, true);
        if (block == Blocks.DEEPSLATE_EMERALD_ORE) return new OreConfig(-16, 0, 11, 3, true, true, false);
        if (block == Blocks.EMERALD_ORE) return new OreConfig(1, 256, 11, 3, true, false, true);
        if (block == Blocks.ANCIENT_DEBRIS) return new OreConfig(8, 22, 2, 3, true, false, false);
        if (block == Blocks.DEEPSLATE_GOLD_ORE) return new OreConfig(-64, 0, 4, 9, true, true, false);
        if (block == Blocks.GOLD_ORE) return new OreConfig(1, 32, 4, 9, true, false, true);
        if (block == Blocks.DEEPSLATE_IRON_ORE) return new OreConfig(-64, 0, 10, 9, true, true, false);
        if (block == Blocks.IRON_ORE) return new OreConfig(1, 72, 10, 9, true, false, true);
        if (block == Blocks.DEEPSLATE_REDSTONE_ORE) return new OreConfig(-64, 0, 8, 8, true, true, false);
        if (block == Blocks.REDSTONE_ORE) return new OreConfig(1, 16, 8, 8, true, false, true);
        if (block == Blocks.DEEPSLATE_LAPIS_ORE) return new OreConfig(-32, 0, 2, 7, true, true, false);
        if (block == Blocks.LAPIS_ORE) return new OreConfig(1, 32, 2, 7, true, false, true);
        if (block == Blocks.DEEPSLATE_COPPER_ORE) return new OreConfig(-16, 0, 16, 10, true, true, false);
        if (block == Blocks.COPPER_ORE) return new OreConfig(1, 112, 16, 10, true, false, true);
        if (block == Blocks.NETHER_GOLD_ORE) return new OreConfig(10, 117, 10, 10, false, false, false);
        if (block == Blocks.NETHER_QUARTZ_ORE) return new OreConfig(10, 117, 16, 14, false, false, false);
        return null;
    }

    private SettingColor getColorForBlock(Block block) {
        if (block == Blocks.DEEPSLATE_COAL_ORE || block == Blocks.COAL_ORE) return new SettingColor(140, 140, 140, 255);
        if (block == Blocks.DEEPSLATE_DIAMOND_ORE || block == Blocks.DIAMOND_ORE) return new SettingColor(0, 230, 255, 255);
        if (block == Blocks.DEEPSLATE_EMERALD_ORE || block == Blocks.EMERALD_ORE) return new SettingColor(0, 255, 80, 255);
        if (block == Blocks.ANCIENT_DEBRIS) return new SettingColor(170, 0, 255, 255);
        if (block == Blocks.DEEPSLATE_GOLD_ORE || block == Blocks.GOLD_ORE || block == Blocks.NETHER_GOLD_ORE) return new SettingColor(255, 215, 0, 255);
        if (block == Blocks.DEEPSLATE_REDSTONE_ORE || block == Blocks.REDSTONE_ORE) return new SettingColor(255, 40, 40, 255);
        if (block == Blocks.DEEPSLATE_LAPIS_ORE || block == Blocks.LAPIS_ORE) return new SettingColor(40, 90, 255, 255);
        return defaultColor.get();
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null) return;
        for (Map.Entry<BlockPos, Block> entry : foundOres.entrySet()) {
            BlockPos pos = entry.getKey();
            Block block = entry.getValue();
            if (hideAir.get() && mc.level != null && mc.level.getBlockState(pos).isAir()) {
                foundOres.remove(pos);
                continue;
            }
            SettingColor line = getColorForBlock(block);
            SettingColor side = new SettingColor(line.r, line.g, line.b, 65);
            AABB box = new AABB(pos);
            event.renderer.box(box, side, line, shapeMode.get(), 0);
            if (tracers.get()) {
                event.renderer.line(
                    RenderUtils.center.x,
                    RenderUtils.center.y,
                    RenderUtils.center.z,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    line
                );
            }
        }
    }

    private record OreConfig(int minY, int maxY, int attempts, int veinSize, boolean triangular, boolean onlyDeepslate, boolean onlyStone) {}
}
