package com.baran.archfixture.applicationadapter.application;

import com.baran.archfixture.applicationadapter.adapters.out.persistence.Store;

public class UseCase {

    private final Store store = new Store();

    public Store store() {
        return store;
    }
}
