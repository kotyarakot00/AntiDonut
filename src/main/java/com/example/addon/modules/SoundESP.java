package com.example.addon.modules;

import com.example.addon.AntiDonut;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.PlaySoundEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class SoundESP extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<List<SoundEvent>> sounds = sgGeneral.add(new SoundEventListSetting.Builder()
        .name("sounds")
        .description("Какие звуки отображать. Если список пуст — показываются все звуки.")
        .build()
    );

    private final Setting<Boolean> ignoreOwn = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-own")
        .description("Игнорировать звуки прямо рядом с вами.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> renderTime = sgGeneral.add(new IntSetting.Builder()
        .name("render-time")
        .description("Время отображения метки в миллисекундах.")
        .defaultValue(2000)
        .min(100)
        .sliderMax(10000)
        .build()
    );

    private final Setting<Double> boxSize = sgRender.add(new DoubleSetting.Builder()
        .name("box-size")
        .description("Размер квадрата в месте звука.")
        .defaultValue(0.25)
        .min(0.05)
        .sliderMax(1.0)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("Режим отрисовки бокса.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Цвет заливки.")
        .defaultValue(new SettingColor(255, 170, 0, 75))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Цвет контура.")
        .defaultValue(new SettingColor(255, 170, 0, 255))
        .build()
    );

    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Рисовать линии от прицела до звука.")
        .defaultValue(false)
        .build()
    );

    private final List<SoundPos> soundPositions = new CopyOnWriteArrayList<>();

    public SoundESP() {
        super(AntiDonut.CATEGORY, "sound-esp", "Отображает источники звуков в мире в виде ESP-боксов.");
    }

    @Override
    public void onDeactivate() {
        soundPositions.clear();
    }

    @EventHandler
    private void onPlaySound(PlaySoundEvent event) {
        if (mc.player == null) return;

        if (!sounds.get().isEmpty()) {
            boolean match = sounds.get().stream()
                .anyMatch(s -> s.location().equals(event.sound.getIdentifier()));
            if (!match) return;
        }

        Vec3 pos = new Vec3(event.sound.getX(), event.sound.getY(), event.sound.getZ());

        if (ignoreOwn.get() && mc.player.position().distanceTo(pos) < 2.5) return;

        soundPositions.add(new SoundPos(pos, System.currentTimeMillis()));
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        long now = System.currentTimeMillis();
        double half = boxSize.get() / 2.0;

        Iterator<SoundPos> iterator = soundPositions.iterator();
        while (iterator.hasNext()) {
            SoundPos sp = iterator.next();
            if (now - sp.time > renderTime.get()) {
                soundPositions.remove(sp);
                continue;
            }

            AABB box = new AABB(
                sp.pos.x - half, sp.pos.y - half, sp.pos.z - half,
                sp.pos.x + half, sp.pos.y + half, sp.pos.z + half
            );

            event.renderer.box(box, sideColor.get(), lineColor.get(), shapeMode.get(), 0);

            if (tracers.get()) {
                event.renderer.line(
                    RenderUtils.center.x,
                    RenderUtils.center.y,
                    RenderUtils.center.z,
                    sp.pos.x, sp.pos.y, sp.pos.z,
                    lineColor.get()
                );
            }
        }
    }

    private record SoundPos(Vec3 pos, long time) {}
}
