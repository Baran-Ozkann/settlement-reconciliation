package com.baran.recon.application.run;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import com.baran.recon.application.port.ItemTotals;
import com.baran.recon.application.port.StageBStore;
import com.baran.recon.domain.item.ItemSide;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.run.ItemTotal;

/**
 * The application's own Stage B store, wrapped so a test can make a bank run's totals by status
 * miscount one side by one item, as a status query that counted an item twice would: the break
 * proof of the run's finalization check over bank lines and batches (INV-1, INV-4, TDD 9.1). Armed
 * per bank source and for one run, so other tests sharing the context are not touched.
 */
public final class MiscountingStageBStore implements StageBStore {

    private static final Comparator<ItemTotal> STABLE = Comparator.comparing((ItemTotal total) -> total.currency().code())
            .thenComparing(total -> total.status().orElseThrow());

    private final StageBStore delegate;
    private final Map<SourceCode, ItemSide> armed = new ConcurrentHashMap<>();

    private MiscountingStageBStore(StageBStore delegate) {
        this.delegate = delegate;
    }

    public static MiscountingStageBStore of(StageBStore injected) {
        return (MiscountingStageBStore) injected;
    }

    /** The bank source's next run counts one item too many among its {@code side} items of one status. */
    public void countOneTooManyInTheNextRunOf(SourceCode bank, ItemSide side) {
        armed.put(bank, side);
    }

    @Override
    public int matchBatchTotals(StageBPass pass) {
        return delegate.matchBatchTotals(pass);
    }

    @Override
    public ItemTotals itemTotals(SourceCode bank, List<SourceCode> pspSources, LocalDate valueDateFrom,
                                 LocalDate valueDateTo) {
        ItemTotals counted = delegate.itemTotals(bank, pspSources, valueDateFrom, valueDateTo);
        ItemSide side = armed.remove(bank);
        if (side == null) {
            return counted;
        }
        List<ItemTotal> byStatus = new ArrayList<>(counted.byStatus());
        ItemTotal first = byStatus.stream().filter(total -> total.side() == side).min(STABLE)
                .orElseThrow(() -> new IllegalStateException("no " + side + " item to miscount"));
        byStatus.set(byStatus.indexOf(first),
                new ItemTotal(side, first.currency(), first.status(), first.items() + 1, first.amount()));
        return new ItemTotals(byStatus, counted.inScope());
    }

    /** Import into a test context to wrap its store. */
    @TestConfiguration(proxyBeanMethods = false)
    public static class Injection {

        @Bean
        static BeanPostProcessor miscountStageBStore() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    return bean instanceof StageBStore store && !(bean instanceof MiscountingStageBStore)
                            ? new MiscountingStageBStore(store)
                            : bean;
                }
            };
        }
    }
}
