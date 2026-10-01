package com.limelight.library;

import android.app.Application;

import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.StringReader;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// Robolectric provides the XmlPullParser implementation that the parser uses on devices.
@Config(sdk = {33}, application = Application.class)
@RunWith(RobolectricTestRunner.class)
public class AppListParsingTest {
    private static List<NvApp> parse(String apps) throws Exception {
        return NvHTTP.getAppListByReader(new StringReader(
                "<?xml version=\"1.0\" encoding=\"utf-8\"?><root status_code=\"200\">" + apps + "</root>"));
    }

    @Test
    public void oldHostHasNoPlatform() throws Exception {
        List<NvApp> apps = parse(
                "<App><IsHdrSupported>1</IsHdrSupported><AppTitle>Example Game</AppTitle>"
                        + "<UUID>11111111-2222-3333-4444-555555555555</UUID><IDX>0</IDX><ID>101</ID>"
                        + "<ArtVersion>abc</ArtVersion></App>"
                        + "<App><AppTitle>Desktop</AppTitle><ID>102</ID></App>");
        assertEquals(2, apps.size());
        assertEquals("Example Game", apps.get(0).getAppName());
        assertEquals(101, apps.get(0).getAppId());
        assertTrue(apps.get(0).isHdrSupported());
        assertEquals("", apps.get(0).getPlatform());
        assertEquals("", apps.get(0).getPlatformId());
        assertEquals("", apps.get(1).getPlatform());
    }

    @Test
    public void newHostSendsPlatform() throws Exception {
        List<NvApp> apps = parse(
                "<App><AppTitle>Sample Kart Racer</AppTitle><UUID>AAAA</UUID><IDX>3</IDX><ID>201</ID>"
                        + "<ArtVersion>1</ArtVersion><Platform>Nintendo Switch</Platform>"
                        + "<PlatformId>nintendo_switch</PlatformId></App>"
                        + "<App><AppTitle>Desktop</AppTitle><ID>202</ID><Platform> Apps </Platform>"
                        + "<PlatformId>apps</PlatformId></App>"
                        + "<App><AppTitle>Example Tool</AppTitle><ID>203</ID>"
                        + "<Platform>Example &amp; Co</Platform></App>");
        assertEquals(3, apps.size());
        assertEquals("Nintendo Switch", apps.get(0).getPlatform());
        assertEquals("nintendo_switch", apps.get(0).getPlatformId());
        assertEquals("Apps", apps.get(1).getPlatform());
        assertEquals("apps", apps.get(1).getPlatformId());
        assertEquals("Example & Co", apps.get(2).getPlatform());
        assertEquals("", apps.get(2).getPlatformId());
        assertTrue(apps.get(0).toString().contains("Platform: Nintendo Switch"));
    }

    @Test
    public void unknownElementsAreIgnored() throws Exception {
        List<NvApp> apps = parse(
                "<App><AppTitle>Example Game</AppTitle><ID>301</ID><FutureField>value</FutureField>"
                        + "<Platform>PC (Windows)</Platform><PlatformId>pc_windows</PlatformId>"
                        + "<AnotherField><Nested>x</Nested></AnotherField></App>");
        assertEquals(1, apps.size());
        assertEquals("Example Game", apps.get(0).getAppName());
        assertEquals("PC (Windows)", apps.get(0).getPlatform());
        assertEquals("pc_windows", apps.get(0).getPlatformId());
    }
}
