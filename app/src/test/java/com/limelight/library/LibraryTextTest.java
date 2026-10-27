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
        // Compatibility forms fold to plain letters and digits
        assertEquals("example 2", LibraryText.normalize("\uFF25\uFF58\uFF41\uFF4D\uFF50\uFF4C\uFF45 \u00B2"));
        // Cached results stay correct for repeated and different inputs
        assertEquals("repeat example", LibraryText.normalize("Repeat Example"));
        assertEquals("repeat example", LibraryText.normalize("Repeat Example"));
        assertEquals("repeat example two", LibraryText.normalize("Repeat Example Two"));
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
