/**
 * The domain: money, items, matching rules, the break state machine. Pure Java (TDD 5.2): nothing
 * outside {@code java.*} and this package, and no floating point anywhere, because money is integer
 * minor units end to end (INV-8). {@code ArchitectureTest} and {@code ci/check-rules.sh} enforce
 * both.
 */
package com.baran.recon.domain;
