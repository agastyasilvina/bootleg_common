package com.example.crud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Only here so the module boots standalone and {@code @SpringBootTest} has a context root to
 * find. Delete it when pasting into the real project — but note that the four classes must then
 * live under your own {@code @SpringBootApplication} package, or component scanning will not
 * reach them and none of these beans will exist.
 */
@SpringBootApplication
public class CrudSavingApplication {

    public static void main(String[] args) {
        SpringApplication.run(CrudSavingApplication.class, args);
    }
}
