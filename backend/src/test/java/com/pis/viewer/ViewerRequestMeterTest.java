package com.pis.viewer;

import static org.assertj.core.api.Assertions.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ViewerRequestMeterTest {
    private final OffsetDateTime minute=OffsetDateTime.parse("2026-01-01T00:00:00Z");
    @Test void repeatedDependencyChecksPayOnceButEveryBinaryByteIsCharged(){
        var meter=new ViewerRequestMeter();var actor=UUID.randomUUID();var units=new AtomicInteger();var bytes=new AtomicInteger();
        ViewerRequestMeter.Charge charge=(at,n,size)->{assertThat(at).isEqualTo(minute);units.addAndGet(n);bytes.addAndGet(size);};
        for(int i=0;i<14;i++)meter.charge(actor,minute,0,charge);
        meter.charge(actor,minute.plusMinutes(1),128,charge);meter.charge(actor,minute.plusMinutes(1),64,charge);
        assertThat(units.get()).isEqualTo(1);assertThat(bytes.get()).isEqualTo(192);
        assertThat(meter.snapshot()).isEqualTo(new ViewerRequestMeter.Snapshot(16,1,192));
        new ViewerRequestMeter().charge(actor,minute,0,(at,n,size)->assertThat(n).isEqualTo(1));
        meter.charge(UUID.randomUUID(),minute,0,(at,n,size)->assertThat(n).isEqualTo(1));
    }
    @Test void failedAccountingDoesNotBecomeAReusablePaidMarker(){
        var meter=new ViewerRequestMeter();var actor=UUID.randomUUID();
        assertThatThrownBy(()->meter.charge(actor,minute,0,(at,n,size)->{throw new IllegalStateException("Synthetic rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(meter.snapshot().requestUnits()).isZero();
        meter.charge(actor,minute,0,(at,n,size)->assertThat(n).isEqualTo(1));
    }
    @Test void rolledBackUnitsAreRepaidButCommittedUnitsSurviveLaterByteRollback(){
        var meter=new ViewerRequestMeter();var actor=UUID.randomUUID();
        meter.charge(actor,minute,64,(at,n,size)->{});
        meter.charge(actor,minute,32,(at,n,size)->{});
        meter.rollback(actor,1,64);meter.rollback(actor,0,32);
        assertThat(meter.snapshot()).isEqualTo(new ViewerRequestMeter.Snapshot(2,0,0));
        meter.charge(actor,minute,0,(at,n,size)->assertThat(n).isEqualTo(1));
        meter.charge(actor,minute,16,(at,n,size)->assertThat(n).isZero());meter.rollback(actor,0,16);
        meter.charge(actor,minute,0,(at,n,size)->{throw new AssertionError("Committed frequency must not be paid twice");});
        assertThat(meter.snapshot()).isEqualTo(new ViewerRequestMeter.Snapshot(5,1,0));
    }
    @Test void concurrentInternalChecksHaveOneCommittedCharge()throws Exception{
        var meter=new ViewerRequestMeter();var actor=UUID.randomUUID();var units=new AtomicInteger();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var jobs=new ArrayList<Future<?>>();for(int i=0;i<16;i++)jobs.add(pool.submit(()->meter.charge(actor,minute,1,(at,n,size)->units.addAndGet(n))));
            for(var job:jobs)job.get(5,TimeUnit.SECONDS);
        }
        assertThat(units.get()).isEqualTo(1);assertThat(meter.snapshot()).isEqualTo(new ViewerRequestMeter.Snapshot(16,1,16));
    }
}
