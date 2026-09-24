package com.baran.recon.archfixture.controllerrepository.adapters.in.web;

import org.springframework.web.bind.annotation.RestController;

import com.baran.recon.archfixture.controllerrepository.adapters.out.persistence.BreakRepository;

@RestController
public class RepositoryController {

    private final BreakRepository repository = new BreakRepository();

    public BreakRepository repository() {
        return repository;
    }
}
