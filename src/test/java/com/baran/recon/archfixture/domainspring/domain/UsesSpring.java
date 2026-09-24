package com.baran.recon.archfixture.domainspring.domain;

import org.springframework.util.StringUtils;

public class UsesSpring {

    public boolean blank(String text) {
        return !StringUtils.hasText(text);
    }
}
