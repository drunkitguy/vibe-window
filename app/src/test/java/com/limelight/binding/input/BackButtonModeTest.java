package com.limelight.binding.input;

import android.app.Activity;
import android.view.InputDevice;
import android.view.KeyEvent;

import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.nvstream.input.KeyboardPacket;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.GameGestures;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyByte;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The Back button setting (list_back_button_mode) on the real ControllerHandler input path.
 * Events use device id 0, the default context, whose Back Android keeps by default (as for
 * built-in controls with their own Select, like the AYN Thor).
 */
@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class})
@RunWith(RobolectricTestRunner.class)
public class BackButtonModeTest {
    private static final int GAMEPAD = InputDevice.SOURCE_GAMEPAD | InputDevice.SOURCE_KEYBOARD;
    private static final int NO_FLAGS = 0;

    private Activity activity;
    private NvConnection conn;
    private PreferenceConfiguration prefs;
    private ControllerHandler handler;
    private final List<Integer> packets = new ArrayList<>();
    private long time = 100_000;

    @Before
    public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        conn = Mockito.mock(NvConnection.class);
        doAnswer(invocation -> {
            packets.add(invocation.getArgument(2));
            return null;
        }).when(conn).sendControllerInput(anyShort(), anyShort(), anyInt(), anyByte(), anyByte(),
                anyShort(), anyShort(), anyShort(), anyShort());
        prefs = PreferenceConfiguration.readPreferences(activity);
        prefs.flipFaceButtons = false;
        prefs.backAsGuide = false;
    }

    @After
    public void tearDown() {
        if (handler != null) {
            handler.destroy();
        }
    }

    private void start(int mode) {
        prefs.backButtonMode = mode;
        handler = new ControllerHandler(activity, conn, Mockito.mock(GameGestures.class), prefs);
    }

    private ControllerHandler.InputDeviceContext context() throws Exception {
        Field field = ControllerHandler.class.getDeclaredField("defaultContext");
        field.setAccessible(true);
        return (ControllerHandler.InputDeviceContext) field.get(handler);
    }

    private KeyEvent key(int action, int keyCode, int repeat, int flags, int source) {
        time += 100;
        // Down time well before the event, so the up path never sleeps
        return new KeyEvent(time - 100, time, action, keyCode, repeat, 0, 0, 0, flags, source);
    }

    private boolean down(int keyCode) {
        return handler.handleButtonDown(key(KeyEvent.ACTION_DOWN, keyCode, 0, NO_FLAGS, GAMEPAD));
    }

    private boolean up(int keyCode) {
        return handler.handleButtonUp(key(KeyEvent.ACTION_UP, keyCode, 0, NO_FLAGS, GAMEPAD));
    }

    private int lastPacket() {
        assertFalse("A controller packet was sent", packets.isEmpty());
        return packets.get(packets.size() - 1);
    }

    // --- Android back: the old path ---

    @Test
    public void androidModeLeavesBackToAndroidAsBefore() {
        start(PreferenceConfiguration.BACK_BUTTON_ANDROID);
        assertFalse(down(KeyEvent.KEYCODE_BACK));
        assertFalse(up(KeyEvent.KEYCODE_BACK));
        assertTrue(packets.isEmpty());
        verify(conn, never()).sendKeyboardInput(anyShort(), anyByte(), anyByte(), anyByte());
    }

    @Test
    public void androidModeOnAControllerThatSendsBackStillSendsSelectOrGuide() throws Exception {
        start(PreferenceConfiguration.BACK_BUTTON_ANDROID);
        context().ignoreBack = false;
        assertTrue(down(KeyEvent.KEYCODE_BACK));
        assertEquals(ControllerPacket.BACK_FLAG, lastPacket());
        assertTrue(up(KeyEvent.KEYCODE_BACK));
        assertEquals(0, lastPacket());

        prefs.backAsGuide = true;
        assertTrue(down(KeyEvent.KEYCODE_BACK));
        assertEquals(ControllerPacket.SPECIAL_BUTTON_FLAG, lastPacket());
        assertTrue(up(KeyEvent.KEYCODE_BACK));
        assertEquals(0, lastPacket());
    }

    // --- Each mode, down and up ---

    @Test
    public void selectSendsTheSelectButton() {
        start(PreferenceConfiguration.BACK_BUTTON_SELECT);
        assertTrue(down(KeyEvent.KEYCODE_BACK));
        assertEquals(ControllerPacket.BACK_FLAG, lastPacket());
        assertTrue(up(KeyEvent.KEYCODE_BACK));
        assertEquals(0, lastPacket());
        verify(conn, never()).sendKeyboardInput(anyShort(), anyByte(), anyByte(), anyByte());
    }

    @Test
    public void touchpadSendsTheTouchpadClick() {
        start(PreferenceConfiguration.BACK_BUTTON_TOUCHPAD);
        assertTrue(down(KeyEvent.KEYCODE_BACK));
        assertEquals(ControllerPacket.TOUCHPAD_FLAG, lastPacket());
        assertTrue(up(KeyEvent.KEYCODE_BACK));
        assertEquals(0, lastPacket());
    }

    @Test
    public void guideSendsGuide() {
        start(PreferenceConfiguration.BACK_BUTTON_GUIDE);
        assertTrue(down(KeyEvent.KEYCODE_BACK));
        assertEquals(ControllerPacket.SPECIAL_BUTTON_FLAG, lastPacket());
        assertTrue(up(KeyEvent.KEYCODE_BACK));
        assertEquals(0, lastPacket());
    }

    @Test
    public void keySendsF13DownThenUpAndNoControllerPacket() {
        start(PreferenceConfiguration.BACK_BUTTON_KEY);
        assertTrue(down(KeyEvent.KEYCODE_BACK));
        // Repeats while held send nothing more
        assertTrue(handler.handleButtonDown(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 1, NO_FLAGS, GAMEPAD)));
        assertTrue(handler.handleButtonDown(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 2, NO_FLAGS, GAMEPAD)));
        verify(conn, times(1)).sendKeyboardInput((short) 0x7C, KeyboardPacket.KEY_DOWN, (byte) 0, (byte) 0);
        verify(conn, never()).sendKeyboardInput(anyShort(), eq(KeyboardPacket.KEY_UP), anyByte(), anyByte());

        assertTrue(up(KeyEvent.KEYCODE_BACK));
        verify(conn, times(1)).sendKeyboardInput((short) 0x7C, KeyboardPacket.KEY_UP, (byte) 0, (byte) 0);
        // A second release has nothing left to release
        assertTrue(up(KeyEvent.KEYCODE_BACK));
        verify(conn, times(1)).sendKeyboardInput((short) 0x7C, KeyboardPacket.KEY_UP, (byte) 0, (byte) 0);
        assertTrue(packets.isEmpty());
    }

    @Test
    public void theNewModeWinsOverBackAsGuide() throws Exception {
        prefs.backAsGuide = true;
        start(PreferenceConfiguration.BACK_BUTTON_TOUCHPAD);
        context().ignoreBack = false;
        assertTrue(down(KeyEvent.KEYCODE_BACK));
        assertEquals(ControllerPacket.TOUCHPAD_FLAG, lastPacket());
        assertTrue(up(KeyEvent.KEYCODE_BACK));
        assertEquals(0, lastPacket());

        packets.clear();
        prefs.backButtonMode = PreferenceConfiguration.BACK_BUTTON_SELECT;
        assertTrue(down(KeyEvent.KEYCODE_BACK));
        assertEquals(ControllerPacket.BACK_FLAG, lastPacket());
        assertTrue(up(KeyEvent.KEYCODE_BACK));
        assertEquals(0, lastPacket());
    }

    // --- What stays with Android ---

    @Test
    public void backFromSomethingThatIsNotAGamepadStaysWithAndroid() {
        start(PreferenceConfiguration.BACK_BUTTON_KEY);
        assertFalse(handler.handleButtonDown(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0, NO_FLAGS, InputDevice.SOURCE_KEYBOARD)));
        assertFalse(handler.handleButtonUp(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0, NO_FLAGS, InputDevice.SOURCE_KEYBOARD)));
        // The soft keyboard's Back stays as it was too
        assertFalse(handler.handleButtonDown(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0, KeyEvent.FLAG_SOFT_KEYBOARD, GAMEPAD)));
        assertFalse(handler.handleButtonUp(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0, KeyEvent.FLAG_SOFT_KEYBOARD, GAMEPAD)));
        verify(conn, never()).sendKeyboardInput(anyShort(), anyByte(), anyByte(), anyByte());
        assertTrue(packets.isEmpty());
    }

    @Test
    public void aControllerWhoseBackIsItsStartKeepsIt() throws Exception {
        start(PreferenceConfiguration.BACK_BUTTON_GUIDE);
        context().ignoreBack = false;
        context().backIsStart = true;
        assertTrue(down(KeyEvent.KEYCODE_BACK));
        assertEquals(ControllerPacket.PLAY_FLAG, lastPacket());
        assertTrue(up(KeyEvent.KEYCODE_BACK));
        assertEquals(0, lastPacket());
    }

    // --- Nothing stays held ---

    @Test
    public void aCancelledReleaseOnFocusLossReleasesTheKey() {
        start(PreferenceConfiguration.BACK_BUTTON_KEY);
        assertTrue(down(KeyEvent.KEYCODE_BACK));
        // Android cancels a held key when the window loses focus
        assertTrue(handler.handleButtonUp(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0, KeyEvent.FLAG_CANCELED, GAMEPAD)));
        verify(conn, times(1)).sendKeyboardInput((short) 0x7C, KeyboardPacket.KEY_UP, (byte) 0, (byte) 0);
    }

    @Test
    public void aCancelledReleaseOnFocusLossClearsTheFlag() {
        for (int mode : new int[] {PreferenceConfiguration.BACK_BUTTON_SELECT,
                PreferenceConfiguration.BACK_BUTTON_TOUCHPAD, PreferenceConfiguration.BACK_BUTTON_GUIDE}) {
            start(mode);
            assertTrue(down(KeyEvent.KEYCODE_BACK));
            assertTrue(lastPacket() != 0);
            assertTrue(handler.handleButtonUp(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0, KeyEvent.FLAG_CANCELED, GAMEPAD)));
            assertEquals("mode " + mode, 0, lastPacket());
            handler.destroy();
            handler = null;
        }
    }

    @Test
    public void stoppingTheStreamMidPressReleasesTheKey() {
        start(PreferenceConfiguration.BACK_BUTTON_KEY);
        assertTrue(down(KeyEvent.KEYCODE_BACK));
        handler.stop();
        verify(conn, times(1)).sendKeyboardInput((short) 0x7C, KeyboardPacket.KEY_UP, (byte) 0, (byte) 0);
        // The release that follows sends nothing more
        up(KeyEvent.KEYCODE_BACK);
        verify(conn, times(1)).sendKeyboardInput((short) 0x7C, KeyboardPacket.KEY_UP, (byte) 0, (byte) 0);
    }

    // --- The combos are unchanged ---

    /** Start+Select presses Guide, then both releases clear everything. */
    private List<Integer> startSelect(int select) {
        packets.clear();
        assertTrue(down(KeyEvent.KEYCODE_BUTTON_START));
        assertTrue(down(select));
        assertTrue(up(select));
        assertTrue(up(KeyEvent.KEYCODE_BUTTON_START));
        return new ArrayList<>(packets);
    }

    /** Select+LB clicks the touchpad when a PlayStation controller is faked for motion. */
    private List<Integer> selectLb(int select) throws Exception {
        context().needsClickpadEmulation = true;
        packets.clear();
        assertTrue(down(select));
        assertTrue(down(KeyEvent.KEYCODE_BUTTON_L1));
        assertTrue(up(KeyEvent.KEYCODE_BUTTON_L1));
        assertTrue(up(select));
        return new ArrayList<>(packets);
    }

    @Test
    public void selectModeDoesStartSelectLikeTheRealSelect() {
        start(PreferenceConfiguration.BACK_BUTTON_SELECT);
        List<Integer> real = startSelect(KeyEvent.KEYCODE_BUTTON_SELECT);
        handler.destroy();
        start(PreferenceConfiguration.BACK_BUTTON_SELECT);
        List<Integer> back = startSelect(KeyEvent.KEYCODE_BACK);

        assertEquals(real, back);
        assertEquals(ControllerPacket.SPECIAL_BUTTON_FLAG, (int) back.get(1));
        assertEquals(0, (int) back.get(back.size() - 1));
    }

    @Test
    public void selectModeDoesSelectLbLikeTheRealSelect() throws Exception {
        start(PreferenceConfiguration.BACK_BUTTON_SELECT);
        List<Integer> real = selectLb(KeyEvent.KEYCODE_BUTTON_SELECT);
        handler.destroy();
        start(PreferenceConfiguration.BACK_BUTTON_SELECT);
        List<Integer> back = selectLb(KeyEvent.KEYCODE_BACK);

        assertEquals(real, back);
        assertEquals(ControllerPacket.TOUCHPAD_FLAG, (int) back.get(1));
        assertEquals(0, (int) back.get(back.size() - 1));
    }

    @Test
    public void theCombosOnTheRealSelectAreUnchangedInEveryMode() throws Exception {
        List<Integer> expectedStartSelect = null;
        List<Integer> expectedSelectLb = null;
        for (int mode = PreferenceConfiguration.BACK_BUTTON_ANDROID; mode <= PreferenceConfiguration.BACK_BUTTON_KEY; mode++) {
            start(mode);
            List<Integer> startSelect = startSelect(KeyEvent.KEYCODE_BUTTON_SELECT);
            handler.destroy();
            start(mode);
            List<Integer> selectLb = selectLb(KeyEvent.KEYCODE_BUTTON_SELECT);
            handler.destroy();
            handler = null;
            if (expectedStartSelect == null) {
                expectedStartSelect = startSelect;
                expectedSelectLb = selectLb;
            }
            assertEquals("Start+Select, mode " + mode, expectedStartSelect, startSelect);
            assertEquals("Select+LB, mode " + mode, expectedSelectLb, selectLb);
        }
        assertEquals(ControllerPacket.SPECIAL_BUTTON_FLAG, (int) expectedStartSelect.get(1));
        assertEquals(ControllerPacket.TOUCHPAD_FLAG, (int) expectedSelectLb.get(1));
    }

    @Test
    public void storedValuesMapToModes() {
        assertEquals(PreferenceConfiguration.BACK_BUTTON_ANDROID, PreferenceConfiguration.backButtonModeFor("android"));
        assertEquals(PreferenceConfiguration.BACK_BUTTON_SELECT, PreferenceConfiguration.backButtonModeFor("select"));
        assertEquals(PreferenceConfiguration.BACK_BUTTON_TOUCHPAD, PreferenceConfiguration.backButtonModeFor("touchpad"));
        assertEquals(PreferenceConfiguration.BACK_BUTTON_GUIDE, PreferenceConfiguration.backButtonModeFor("guide"));
        assertEquals(PreferenceConfiguration.BACK_BUTTON_KEY, PreferenceConfiguration.backButtonModeFor("key"));
        assertEquals(PreferenceConfiguration.BACK_BUTTON_ANDROID, PreferenceConfiguration.backButtonModeFor(null));
        assertEquals(PreferenceConfiguration.BACK_BUTTON_ANDROID, PreferenceConfiguration.backButtonModeFor("bogus"));
    }
}
