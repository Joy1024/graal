/*
 * Copyright (c) 2013, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.graalvm.compiler.hotspot.amd64;

import static jdk.vm.ci.amd64.AMD64.rax;
import static jdk.vm.ci.amd64.AMD64.rdx;
import static jdk.vm.ci.meta.Value.ILLEGAL;
import static org.graalvm.compiler.hotspot.HotSpotBackend.EXCEPTION_HANDLER;
import static org.graalvm.compiler.hotspot.HotSpotBackend.EXCEPTION_HANDLER_IN_CALLER;
import static org.graalvm.compiler.hotspot.HotSpotBackend.Options.GraalArithmeticStubs;
import static org.graalvm.compiler.hotspot.HotSpotForeignCallLinkage.JUMP_ADDRESS;
import static org.graalvm.compiler.hotspot.HotSpotForeignCallLinkage.RegisterEffect.COMPUTES_REGISTERS_KILLED;
import static org.graalvm.compiler.hotspot.HotSpotForeignCallLinkage.RegisterEffect.DESTROYS_ALL_CALLER_SAVE_REGISTERS;
import static org.graalvm.compiler.hotspot.meta.HotSpotForeignCallDescriptor.Reexecutability.NOT_REEXECUTABLE;
import static org.graalvm.compiler.hotspot.meta.HotSpotForeignCallDescriptor.Reexecutability.REEXECUTABLE;
import static org.graalvm.compiler.hotspot.meta.HotSpotForeignCallDescriptor.Transition.LEAF;
import static org.graalvm.compiler.replacements.nodes.BinaryMathIntrinsicNode.BinaryOperation.POW;
import static org.graalvm.compiler.replacements.nodes.UnaryMathIntrinsicNode.UnaryOperation.COS;
import static org.graalvm.compiler.replacements.nodes.UnaryMathIntrinsicNode.UnaryOperation.EXP;
import static org.graalvm.compiler.replacements.nodes.UnaryMathIntrinsicNode.UnaryOperation.LOG;
import static org.graalvm.compiler.replacements.nodes.UnaryMathIntrinsicNode.UnaryOperation.LOG10;
import static org.graalvm.compiler.replacements.nodes.UnaryMathIntrinsicNode.UnaryOperation.SIN;
import static org.graalvm.compiler.replacements.nodes.UnaryMathIntrinsicNode.UnaryOperation.TAN;

import org.graalvm.compiler.core.common.LIRKind;
import org.graalvm.compiler.core.common.spi.ForeignCallDescriptor;
import org.graalvm.compiler.hotspot.GraalHotSpotVMConfig;
import org.graalvm.compiler.hotspot.HotSpotForeignCallLinkage;
import org.graalvm.compiler.hotspot.HotSpotForeignCallLinkageImpl;
import org.graalvm.compiler.hotspot.HotSpotGraalRuntimeProvider;
import org.graalvm.compiler.hotspot.meta.HotSpotForeignCallDescriptor;
import org.graalvm.compiler.hotspot.meta.HotSpotHostForeignCallsProvider;
import org.graalvm.compiler.hotspot.meta.HotSpotProviders;
import org.graalvm.compiler.hotspot.stubs.IntrinsicStubsGen;
import org.graalvm.compiler.hotspot.stubs.SnippetStub;
import org.graalvm.compiler.options.OptionValues;
import org.graalvm.compiler.replacements.amd64.AMD64ArrayEqualsWithMaskForeignCalls;
import org.graalvm.compiler.replacements.amd64.AMD64CalcStringAttributesForeignCalls;
import org.graalvm.compiler.replacements.nodes.ArrayCompareToForeignCalls;
import org.graalvm.compiler.replacements.nodes.ArrayCopyWithConversionsForeignCalls;
import org.graalvm.compiler.replacements.nodes.ArrayEqualsForeignCalls;
import org.graalvm.compiler.replacements.nodes.ArrayIndexOfForeignCalls;
import org.graalvm.compiler.replacements.nodes.ArrayRegionCompareToForeignCalls;
import org.graalvm.compiler.replacements.nodes.VectorizedMismatchForeignCalls;
import org.graalvm.compiler.word.WordTypes;

import jdk.vm.ci.code.CallingConvention;
import jdk.vm.ci.code.CodeCacheProvider;
import jdk.vm.ci.code.RegisterValue;
import jdk.vm.ci.code.TargetDescription;
import jdk.vm.ci.hotspot.HotSpotJVMCIRuntime;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.PlatformKind;
import jdk.vm.ci.meta.Value;

public class AMD64HotSpotForeignCallsProvider extends HotSpotHostForeignCallsProvider {

    private final Value[] nativeABICallerSaveRegisters;

    public AMD64HotSpotForeignCallsProvider(HotSpotJVMCIRuntime jvmciRuntime, HotSpotGraalRuntimeProvider runtime, MetaAccessProvider metaAccess, CodeCacheProvider codeCache,
                    WordTypes wordTypes, Value[] nativeABICallerSaveRegisters) {
        super(jvmciRuntime, runtime, metaAccess, codeCache, wordTypes);
        this.nativeABICallerSaveRegisters = nativeABICallerSaveRegisters;
    }

    @Override
    public void initialize(HotSpotProviders providers, OptionValues options) {
        TargetDescription target = providers.getCodeCache().getTarget();
        PlatformKind word = target.arch.getWordKind();

        // The calling convention for the exception handler stub is (only?) defined in
        // TemplateInterpreterGenerator::generate_throw_exception()
        // in templateInterpreter_x86_64.cpp around line 1923
        RegisterValue exception = rax.asValue(LIRKind.reference(word));
        RegisterValue exceptionPc = rdx.asValue(LIRKind.value(word));
        CallingConvention exceptionCc = new CallingConvention(0, ILLEGAL, exception, exceptionPc);
        register(new HotSpotForeignCallLinkageImpl(EXCEPTION_HANDLER, 0L, DESTROYS_ALL_CALLER_SAVE_REGISTERS, exceptionCc, null));
        register(new HotSpotForeignCallLinkageImpl(EXCEPTION_HANDLER_IN_CALLER, JUMP_ADDRESS, DESTROYS_ALL_CALLER_SAVE_REGISTERS, exceptionCc, null));

        linkSnippetStubs(providers, options, IntrinsicStubsGen::new, ArrayIndexOfForeignCalls.STUBS_AMD64);
        linkSnippetStubs(providers, options, IntrinsicStubsGen::new, ArrayEqualsForeignCalls.STUBS);
        linkSnippetStubs(providers, options, IntrinsicStubsGen::new, ArrayCompareToForeignCalls.STUBS);
        linkSnippetStubs(providers, options, IntrinsicStubsGen::new, ArrayRegionCompareToForeignCalls.STUBS);
        linkSnippetStubs(providers, options, IntrinsicStubsGen::new, VectorizedMismatchForeignCalls.STUB);
        linkSnippetStubs(providers, options, IntrinsicStubsGen::new, ArrayCopyWithConversionsForeignCalls.STUBS);
        linkSnippetStubs(providers, options, AMD64HotspotIntrinsicStubsGen::new, AMD64ArrayEqualsWithMaskForeignCalls.STUBS);
        linkSnippetStubs(providers, options, AMD64HotspotIntrinsicStubsGen::new, AMD64CalcStringAttributesForeignCalls.STUBS);
        super.initialize(providers, options);
    }

    @FunctionalInterface
    private interface SnippetStubConstructor<A extends SnippetStub> {
        A apply(OptionValues options, HotSpotProviders providers, HotSpotForeignCallLinkage linkage);
    }

    private <A extends SnippetStub> void linkSnippetStubs(HotSpotProviders providers, OptionValues options, SnippetStubConstructor<A> constructor, ForeignCallDescriptor... stubs) {
        for (ForeignCallDescriptor stub : stubs) {
            HotSpotForeignCallDescriptor.Reexecutability reexecutability = stub.isReexecutable() ? REEXECUTABLE : NOT_REEXECUTABLE;
            link(constructor.apply(options, providers, registerStubCall(stub.getSignature(), LEAF, reexecutability, COMPUTES_REGISTERS_KILLED, stub.getKilledLocations())));
        }
    }

    @Override
    public Value[] getNativeABICallerSaveRegisters() {
        return nativeABICallerSaveRegisters;
    }

    @Override
    protected void registerMathStubs(GraalHotSpotVMConfig hotSpotVMConfig, HotSpotProviders providers, OptionValues options) {
        if (GraalArithmeticStubs.getValue(options)) {
            link(new AMD64MathStub(SIN, options, providers, registerStubCall(SIN.foreignCallSignature, LEAF, REEXECUTABLE, COMPUTES_REGISTERS_KILLED, NO_LOCATIONS)));
            link(new AMD64MathStub(COS, options, providers, registerStubCall(COS.foreignCallSignature, LEAF, REEXECUTABLE, COMPUTES_REGISTERS_KILLED, NO_LOCATIONS)));
            link(new AMD64MathStub(TAN, options, providers, registerStubCall(TAN.foreignCallSignature, LEAF, REEXECUTABLE, COMPUTES_REGISTERS_KILLED, NO_LOCATIONS)));
            link(new AMD64MathStub(EXP, options, providers, registerStubCall(EXP.foreignCallSignature, LEAF, REEXECUTABLE, COMPUTES_REGISTERS_KILLED, NO_LOCATIONS)));
            link(new AMD64MathStub(LOG, options, providers, registerStubCall(LOG.foreignCallSignature, LEAF, REEXECUTABLE, COMPUTES_REGISTERS_KILLED, NO_LOCATIONS)));
            link(new AMD64MathStub(LOG10, options, providers, registerStubCall(LOG10.foreignCallSignature, LEAF, REEXECUTABLE, COMPUTES_REGISTERS_KILLED, NO_LOCATIONS)));
            link(new AMD64MathStub(POW, options, providers, registerStubCall(POW.foreignCallSignature, LEAF, REEXECUTABLE, COMPUTES_REGISTERS_KILLED, NO_LOCATIONS)));
        } else {
            super.registerMathStubs(hotSpotVMConfig, providers, options);
        }
    }

}
