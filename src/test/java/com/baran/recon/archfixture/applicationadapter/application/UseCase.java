package com.baran.recon.archfixture.applicationadapter.application;

import com.baran.recon.archfixture.applicationadapter.adapters.out.persistence.Store;

public class UseCase {

    private final Store store = new Store();

    public Store store() {
        return store;
    }
}
