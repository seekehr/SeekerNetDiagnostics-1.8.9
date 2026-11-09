package com.seekehr.netdiagnostics.mod;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HudConfigTest {

    /** Forge's Configuration reads the game directory from FML start-up data, which tests don't have. */
    @BeforeClass
    public static void fakeGameDirectory() throws Exception {
        Class<?> c = Class.forName("net.minecraftforge.fml.relauncher.FMLInjectionData");
        Field f = c.getDeclaredField("minecraftHome");
        f.setAccessible(true);
        if (f.get(null) == null) {
            f.set(null, new File(System.getProperty("java.io.tmpdir")));
        }
    }

    @Test
    public void everySettingSurvivesARestart() throws Exception {
        File f = File.createTempFile("snd", ".cfg");
        f.delete();
        f.deleteOnExit();
        HudConfig.load(f);
        assertTrue(f.isFile()); // defaults written on first load
        assertTrue(HudConfig.enabled);
        assertTrue(HudConfig.autoMonitor);
        assertEquals(1000, HudConfig.pingIntervalMs);
        assertEquals(60, HudConfig.traceIntervalSec);

        HudConfig.enabled = false;
        HudConfig.x = 120;
        HudConfig.y = 40;
        HudConfig.autoMonitor = false;
        HudConfig.pingIntervalMs = 2000;
        HudConfig.advancedTracing = true;
        HudConfig.traceIntervalSec = 15;
        HudConfig.traceTab = true;
        HudConfig.style = PingHud.STYLE_BAR;
        HudConfig.textColor = 3;
        HudConfig.latencyColors = true;
        HudConfig.shadow = true;
        HudConfig.bgOpacity = 85;
        HudConfig.scalePercent = 150;
        HudConfig.stacked = true;
        HudConfig.showMin = true;
        HudConfig.showMax = true;
        HudConfig.showTabPing = true;
        HudConfig.save();

        // simulate a game restart: reset the fields, then load the same file
        HudConfig.enabled = true;
        HudConfig.x = 0;
        HudConfig.y = 0;
        HudConfig.autoMonitor = true;
        HudConfig.pingIntervalMs = 1000;
        HudConfig.advancedTracing = false;
        HudConfig.traceIntervalSec = 60;
        HudConfig.traceTab = false;
        HudConfig.resetStyle();
        HudConfig.showMin = false;
        HudConfig.showMax = false;
        HudConfig.showTabPing = false;
        HudConfig.load(f);

        assertFalse(HudConfig.enabled);
        assertEquals(120, HudConfig.x);
        assertEquals(40, HudConfig.y);
        assertFalse(HudConfig.autoMonitor);
        assertEquals(2000, HudConfig.pingIntervalMs);
        assertTrue(HudConfig.advancedTracing);
        assertEquals(15, HudConfig.traceIntervalSec);
        assertTrue(HudConfig.traceTab);
        assertEquals(PingHud.STYLE_BAR, HudConfig.style);
        assertEquals(3, HudConfig.textColor);
        assertTrue(HudConfig.latencyColors);
        assertTrue(HudConfig.shadow);
        assertEquals(85, HudConfig.bgOpacity);
        assertEquals(150, HudConfig.scalePercent);
        assertTrue(HudConfig.stacked);
        assertTrue(HudConfig.showMin);
        assertTrue(HudConfig.showMax);
        assertTrue(HudConfig.showTabPing);
        assertFalse(HudConfig.showAvg);
    }

    @Test
    public void invalidValuesFallBackToDefaults() throws Exception {
        File f = File.createTempFile("snd", ".cfg");
        f.deleteOnExit();
        Writer w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
        w.write("monitor {\n    I:pingIntervalMs=1234\n}\ntrace {\n    I:updateIntervalSeconds=45\n}\n");
        w.close();
        HudConfig.load(f);
        assertEquals(1000, HudConfig.pingIntervalMs);
        assertEquals(60, HudConfig.traceIntervalSec);
    }

    @Test
    public void invalidHudStyleValuesFallBackToDefaults() throws Exception {
        File f = File.createTempFile("snd", ".cfg");
        f.deleteOnExit();
        Writer w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
        w.write("hud {\n    style {\n        I:style=99\n        I:textColor=42\n        I:backgroundOpacity=33\n"
                + "        I:scalePercent=110\n    }\n}\n");
        w.close();
        HudConfig.load(f);
        assertEquals(PingHud.STYLE_BOX, HudConfig.style);
        assertEquals(0, HudConfig.textColor);
        assertEquals(HudConfig.DEFAULT_BG_OPACITY, HudConfig.bgOpacity);
        assertEquals(100, HudConfig.scalePercent);
    }
}
