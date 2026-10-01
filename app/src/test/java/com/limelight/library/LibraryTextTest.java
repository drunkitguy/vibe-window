package com.limelight.library;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class LibraryTextTest {
    @Test
    public void normalize() {
        assertEquals("cafe example", LibraryText.normalize("  Café  EXAMPLE \t"));
        assertEquals("example", LibraryText.normalize("​Ex‍am﻿ple⁠"));
        assertEquals("", LibraryText.normalize(null));
        assertEquals("", LibraryText.normalize(" ​ "));
        assertEquals("uber noel", LibraryText.normalize("Über Noël"));
    }

    @Test
    public void tokens() {
        assertEquals(Arrays.asList("kart", "racer"), LibraryText.tokens("  Kart ​ Racer "));
        assertEquals(Collections.emptyList(), LibraryText.tokens(" ​ "));
    }

    @Test
    public void clean() {
        assertEquals("Nintendo Switch", LibraryText.clean("​ Nintendo Switch \n"));
        assertEquals("", LibraryText.clean(null));
    }
}
