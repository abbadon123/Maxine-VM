/*
 * Copyright (c) 2007 Sun Microsystems, Inc.  All rights reserved.
 *
 * Sun Microsystems, Inc. has intellectual property rights relating to technology embodied in the product
 * that is described in this document. In particular, and without limitation, these intellectual property
 * rights may include one or more of the U.S. patents listed at http://www.sun.com/patents and one or
 * more additional patents or pending patent applications in the U.S. and in other countries.
 *
 * U.S. Government Rights - Commercial software. Government users are subject to the Sun
 * Microsystems, Inc. standard license agreement and applicable provisions of the FAR and its
 * supplements.
 *
 * Use is subject to license terms. Sun, Sun Microsystems, the Sun logo, Java and Solaris are trademarks or
 * registered trademarks of Sun Microsystems, Inc. in the U.S. and other countries. All SPARC trademarks
 * are used under license and are trademarks or registered trademarks of SPARC International, Inc. in the
 * U.S. and other countries.
 *
 * UNIX is a registered trademark in the U.S. and other countries, exclusively licensed through X/Open
 * Company, Ltd.
 */
package com.sun.max.vm.stack;

import static com.sun.max.vm.VMOptions.*;
import static com.sun.max.vm.stack.StackFrameWalker.Purpose.*;
import static com.sun.max.vm.thread.VmThreadLocal.*;

import java.util.*;

import com.sun.max.annotate.*;
import com.sun.max.collect.*;
import com.sun.max.unsafe.*;
import com.sun.max.vm.*;
import com.sun.max.vm.actor.member.*;
import com.sun.max.vm.bytecode.*;
import com.sun.max.vm.code.*;
import com.sun.max.vm.compiler.*;
import com.sun.max.vm.compiler.snippet.NativeStubSnippet.*;
import com.sun.max.vm.compiler.target.*;
import com.sun.max.vm.jni.*;
import com.sun.max.vm.runtime.*;
import com.sun.max.vm.runtime.VMRegister.*;
import com.sun.max.vm.thread.*;

/**
 * The mechanism for iterating over the frames in a thread's stack.
 *
 * @author Doug Simon
 * @author Laurent Daynes
 */
public abstract class StackFrameWalker {

    /**
     * A VM option for enabling stack frame walk tracing.
     */
    public static final VMBooleanXXOption TRACE_STACK_WALK = register(new VMBooleanXXOption("-XX:-TraceStackWalk", ""), MaxineVM.Phase.STARTING);

    /**
     * The cursor class encapsulates all the state associated with a single stack frame,
     * including the target method, the instruction pointer, stack pointer, frame pointer, register state,
     * and whether the frame is the top frame. The stack frame walker manages two cursors internally: the current
     * frame (caller frame) and the last frame (callee frame) and destructively updates their contents
     * when walking the stack, rather than allocating new ones, since allocation is disallowed when
     * walking the stack for reference map preparation.
     */
    public final class Cursor {

        private TargetMethod targetMethod;
        private Pointer instructionPointer = Pointer.zero();
        private Pointer stackPointer = Pointer.zero();
        private Pointer framePointer = Pointer.zero();
        private Pointer registerState = Pointer.zero();
        private boolean isTopFrame = false;

        private Cursor() {
        }

        /**
         * Updates the cursor to point to the next stack frame.
         * This method implicitly sets {@link Cursor#isTopFrame()} of this cursor to {@code false} and
         * {@link Cursor#targetMethod()} of this cursor to {@code null}.
         * @param instructionPointer the new instruction pointer
         * @param stackPointer the new stack pointer
         * @param framePointer the new frame pointer
         */
        public void advance(Pointer instructionPointer, Pointer stackPointer, Pointer framePointer) {
            setFields(null, instructionPointer, stackPointer, framePointer, Pointer.zero(), false);
        }

        private void reset() {
            setFields(null, Pointer.zero(), Pointer.zero(), Pointer.zero(), Pointer.zero(), false);
        }

        private void copyFrom(Cursor other) {
            setFields(other.targetMethod, other.instructionPointer, other.stackPointer, other.framePointer, other.registerState, other.isTopFrame);
        }

        private Cursor setFields(TargetMethod targetMethod, Pointer ip, Pointer sp, Pointer fp, Pointer registerState, boolean isTopFrame) {
            this.targetMethod = targetMethod;
            this.instructionPointer = ip;
            this.stackPointer = sp;
            this.framePointer = fp;
            this.registerState = registerState;
            this.isTopFrame = isTopFrame;
            return this;
        }

        private Cursor copy() {
            return new Cursor().setFields(targetMethod, instructionPointer, stackPointer, framePointer, registerState, isTopFrame);
        }

        /**
         * @return the stack frame walker for this cursor
         */
        public StackFrameWalker stackFrameWalker() {
            return StackFrameWalker.this;
        }

        /**
         * @return the target method corresponding to the instruction pointer.
         */
        public final TargetMethod targetMethod() {
            return targetMethod;
        }

        /**
         * @return the current instruction pointer.
         */
        public final Pointer instructionPointer() {
            return instructionPointer;
        }

        /**
         * @return the current stack pointer.
         */
        public final Pointer stackPointer() {
            return stackPointer;
        }

        /**
         * @return the current frame pointer.
         */
        public final Pointer framePointer() {
            return framePointer;
        }

        /**
         * @return the register state within this frame, if any.
         */
        public final Pointer registerState() {
            return registerState;
        }

        /**
         * @return {@code true} if this frame is the top frame
         */
        public final boolean isTopFrame() {
            return isTopFrame;
        }
    }

    protected StackFrameWalker() {
    }

    /**
     * Constants denoting the finite set of reasons for which a stack walk can be performed.
     * Every implementation of {@link RuntimeCompilerScheme#walkFrame(com.sun.max.vm.stack.StackFrameWalker.Cursor, com.sun.max.vm.stack.StackFrameWalker.Cursor,boolean,com.sun.max.vm.compiler.target.TargetMethod, com.sun.max.vm.stack.StackFrameWalker.Purpose, Object)}
     * must deal with each type of stack walk.
     *
     * @author Doug Simon
     */
    public enum Purpose {
        /**
         * Raising an exception.
         * This type of stack walk is allocation free.
         */
        EXCEPTION_HANDLING(StackUnwindingContext.class),

        /**
         * Preparing {@linkplain StackReferenceMapPreparer stack reference map} for a thread's stack.
         * This type of stack walk is allocation free.
         */
        REFERENCE_MAP_PREPARING(StackReferenceMapPreparer.class),

        /**
         * Reflecting on the frames of a thread's stack.
         * This type of stack walk is allocation free.
         */
        RAW_INSPECTING(RawStackFrameVisitor.class),

        /**
         * Reflecting on the frames of a thread's stack.
         * This type of stack walk is not allocation free.
         */
        INSPECTING(StackFrameVisitor.class);

        private final Class contextType;

        private Purpose(Class contextType) {
            this.contextType = contextType;
        }

        /**
         * Determines if a given context object is of the type expected by this purpose.
         */
        public final boolean isValidContext(Object context) {
            return contextType.isInstance(context);
        }
    }

    private final Cursor current = new Cursor();
    private final Cursor callee = new Cursor();

    private Purpose purpose = null;
    private Pointer currentAnchor;
    private StackFrame calleeStackFrame;
    private Pointer trapState;

    /**
     * Walks a thread's stack.
     * <p>
     * Note that this method does not explicitly {@linkplain #reset() reset} this stack walker. If this walk is for the
     * purpose of raising an exception, then the code that unwinds the stack to the exception handler frame is expected
     * to reset this walker. For all other purposes, the caller of this method must reset this walker.
     *
     * @param instructionPointer the instruction pointer of the code executing in the top frame
     * @param stackPointer a pointer denoting an ISA defined location in the top frame
     * @param framePointer a pointer denoting an ISA defined location in the top frame
     * @param purpose the reason this walk is being performed
     * @param context a purpose-specific object of a type {@linkplain Purpose#isValidContext(Object) compatible} with
     *            {@code purpose}
     */
    private void walk(Pointer instructionPointer, Pointer stackPointer, Pointer framePointer, Purpose purpose, Object context) {
        checkPurpose(purpose, context);

        current.reset();
        callee.reset();

        current.instructionPointer = instructionPointer;
        current.stackPointer = stackPointer;
        current.framePointer = framePointer;
        current.isTopFrame = true;

        this.trapState = Pointer.zero();
        this.purpose = purpose;
        this.currentAnchor = readPointer(LAST_JAVA_FRAME_ANCHOR);
        boolean isTopFrame = true;
        boolean inNative = !currentAnchor.isZero() && !readWord(currentAnchor, JavaFrameAnchor.PC.offset).isZero();

        TargetMethod lastJavaCallee = null;

        while (!current.stackPointer.isZero()) {
            TargetMethod targetMethod = targetMethodFor(current.instructionPointer);
            current.targetMethod = targetMethod;
            if (targetMethod != null && (!inNative || (purpose == INSPECTING || purpose == RAW_INSPECTING))) {

                if (inNative) {
                    inNative = false;
                }
                // do tracing
                traceWalkTargetMethod(isTopFrame, targetMethod);

                // Java frame
                checkVmEntrypointCaller(lastJavaCallee, targetMethod);

                final RuntimeCompilerScheme compilerScheme = targetMethod.compilerScheme;

                // Record the last Java callee frame info based on the current frame *before* the
                // compiler scheme updates the current frame during the call to walkFrame() below

                if (!compilerScheme.walkFrame(current, callee, isTopFrame, lastJavaCallee, purpose, context)) {
                    break;
                }
                lastJavaCallee = targetMethod;
                if (targetMethod.classMethodActor() == null || !targetMethod.classMethodActor().isTrapStub()) {
                    trapState = Pointer.zero();
                }
            } else {
                // do tracing
                traceWalkNative();
                if (purpose == INSPECTING) {
                    final StackFrameVisitor stackFrameVisitor = (StackFrameVisitor) context;
                    if (!stackFrameVisitor.visitFrame(new NativeStackFrame(calleeStackFrame, current.instructionPointer, current.framePointer, current.stackPointer))) {
                        break;
                    }
                } else if (purpose == RAW_INSPECTING) {
                    final RawStackFrameVisitor stackFrameVisitor = (RawStackFrameVisitor) context;
                    final int flags = RawStackFrameVisitor.Util.makeFlags(isTopFrame, false);
                    if (!stackFrameVisitor.visitFrame(null, current.instructionPointer, current.framePointer, current.stackPointer, flags)) {
                        break;
                    }
                }

                if (inNative) {
                    inNative = false;
                    advanceFrameInNative(purpose);
                } else {
                    if (lastJavaCallee == null) {
                        // This is a native function that called a VM entry point such as the VmThread.run(),
                        // MaxineVM.run() or a JNI function.
                        break;
                    }

                    ClassMethodActor lastJavaCalleeMethodActor = lastJavaCallee.classMethodActor();
                    if (lastJavaCalleeMethodActor != null && lastJavaCalleeMethodActor.isVmEntryPoint()) {
                        if (lastJavaCalleeMethodActor.isTrapStub()) {
                            // This can only occur in the inspector and implies that execution is in the platform specific
                            // prologue of Trap.trapStub() before the point where the trap frame has been completed. In
                            // particular, the return instruction pointer slot has not been updated with the instruction
                            // pointer at which the fault occurred.
                            break;
                        }
                        if (!advanceVmEntryPointFrame(lastJavaCallee)) {
                            break;
                        }
                    } else if (lastJavaCalleeMethodActor == null) {
                        FatalError.unexpected("Unrecognized target method without a class method actor!");
                    } else {
                        Log.print("Native code called/entered a Java method that is not a JNI function, a Java trap stub or a VM/thread entry point: ");
                        Log.print(lastJavaCalleeMethodActor.name.string);
                        Log.print(lastJavaCalleeMethodActor.descriptor().string);
                        Log.print(" in ");
                        Log.println(lastJavaCalleeMethodActor.holder().name.string);
                        FatalError.unexpected("Native code called/entered a Java method that is not a JNI function, a Java trap stub or a VM/thread entry point");
                    }
                }
                lastJavaCallee = null;
            }
            isTopFrame = false;
        }
    }

    private void traceWalkPurpose(Purpose purpose) {
        if (TRACE_STACK_WALK.getValue()) {
            Log.print("StackFrameWalk: Start stack frame walk for purpose ");
            Log.println(purpose);
        }
    }

    private void traceWalkNative() {
        if (TRACE_STACK_WALK.getValue()) {
            Log.print("StackFrameWalk: Frame for native function [IP=");
            Log.print(current.instructionPointer);
            Log.println(']');
        }
    }

    private void traceWalkTargetMethod(boolean topFrame, TargetMethod targetMethod) {
        if (TRACE_STACK_WALK.getValue()) {
            Log.print("StackFrameWalk: Frame for ");
            if (targetMethod.classMethodActor() == null) {
                Log.print(targetMethod.description());
            } else {
                Log.printMethod(targetMethod.classMethodActor(), false);
            }
            Log.print(", pc=");
            Log.print(current.instructionPointer);
            Log.print("[");
            Log.print(targetMethod.codeStart());
            Log.print("+");
            Log.print(current.instructionPointer.minus(targetMethod.codeStart()).toInt());
            Log.print("], isTopFrame=");
            Log.print(topFrame);
            Log.println("");
        }
    }

    private void checkVmEntrypointCaller(TargetMethod lastJavaCallee, final TargetMethod targetMethod) {
        if (lastJavaCallee != null && lastJavaCallee.classMethodActor() != null) {
            final ClassMethodActor classMethodActor = lastJavaCallee.classMethodActor();
            if (classMethodActor.isVmEntryPoint() && !classMethodActor.isTrapStub()) {
                Log.print("Caller of VM entry point (@VM_ENTRY_POINT annotated method) \"");
                Log.print(lastJavaCallee.description());
                Log.print("\" is not native code: ");
                Log.print(targetMethod.description());
                Log.print(targetMethod.classMethodActor().descriptor().string);
                Log.print(" in ");
                Log.println(targetMethod.classMethodActor().holder().name.string);
                FatalError.unexpected("Caller of a VM entry point (@VM_ENTRY_POINT method) must be native code");
            }
        }
    }

    /**
     * Advances this stack walker through the frame of a method annotated with {@link VM_ENTRY_POINT}.
     *
     * @param lastJavaCallee the last java method called
     * @return true if the stack walker was advanced
     */
    private boolean advanceVmEntryPointFrame(TargetMethod lastJavaCallee) {
        final ClassMethodActor lastJavaCalleeMethodActor = lastJavaCallee.classMethodActor();
        if (lastJavaCalleeMethodActor != null && lastJavaCalleeMethodActor.isVmEntryPoint()) {
            Pointer anchor = nextNativeStubAnchor();
            if (anchor.isZero()) {
                return false;
            }
            final Word lastJavaCallerInstructionPointer = readWord(anchor, JavaFrameAnchor.PC.offset);
            final Word lastJavaCallerStackPointer = readWord(anchor, JavaFrameAnchor.SP.offset);
            final Word lastJavaCallerFramePointer = readWord(anchor, JavaFrameAnchor.FP.offset);
            advance(getNativeFunctionCallInstructionPointerInNativeStub(lastJavaCallerInstructionPointer.asPointer(), true),
                    lastJavaCallerStackPointer,
                    lastJavaCallerFramePointer);
            return true;
        }
        return false;
    }

    /**
     * Advances this walker past the first frame encountered when walking the stack of a thread that is executing
     * in native code.
     */
    private void advanceFrameInNative(Purpose purpose) {
        Pointer anchor = nextNativeStubAnchor();
        FatalError.check(!anchor.isZero(), "No native stub frame anchor found when executing 'in native'");
        Pointer lastJavaCallerInstructionPointer = readWord(anchor, JavaFrameAnchor.PC.offset).asPointer();
        if (lastJavaCallerInstructionPointer.isZero()) {
            FatalError.check(!lastJavaCallerInstructionPointer.isZero(), "Thread cannot be 'in native' without having recorded the last Java caller in thread locals");
        }
        advance(getNativeFunctionCallInstructionPointerInNativeStub(lastJavaCallerInstructionPointer, purpose != INSPECTING && purpose != RAW_INSPECTING),
            readWord(anchor, JavaFrameAnchor.SP.offset),
            readWord(anchor, JavaFrameAnchor.FP.offset));
    }

    /**
     * Gets the next anchor in a VM exit frame. That is, get the frame in the next {@linkplain NativeStubGenerator native stub} on the stack.
     *
     * @return {@link Pointer#zero()} if there are no more native stub frame anchors
     */
    private Pointer nextNativeStubAnchor() {
        if (currentAnchor.isZero()) {
            // We're at a VM entry point that has no Java frames above it.
            return Pointer.zero();
        }

        // Skip over an anchor in a VM entry point frame. The test is the same as JavaFrameAnchor.inJava()
        // except we can't use the latter here if in a separate address space (e.g. the Inspector) than the VM.
        Pointer pc = readWord(currentAnchor, JavaFrameAnchor.PC.offset).asPointer();
        if (pc.isZero()) {
            currentAnchor = readWord(currentAnchor, JavaFrameAnchor.PREVIOUS.offset).asPointer();
            if (currentAnchor.isZero()) {
                // We're at a VM entry point that has no Java frames above it.
                return Pointer.zero();
            }
        }

        pc = readWord(currentAnchor, JavaFrameAnchor.PC.offset).asPointer();
        if (pc.isZero()) {
            // Java frame anchors should always alternate between VM entry and exit frames.
            FatalError.unexpected("Found two adjacent VM entry point frame anchors");
        }
        Pointer anchor = this.currentAnchor;
        this.currentAnchor = readWord(anchor, JavaFrameAnchor.PREVIOUS.offset).asPointer();
        return anchor;
    }

    private void checkPurpose(Purpose purpose, Object context) {
        traceWalkPurpose(purpose);
        if (!purpose.isValidContext(context)) {
            FatalError.unexpected("Invalid stack walk context");
        }

        if (!current.stackPointer.isZero()) {
            Log.print("Stack walker already in use for ");
            Log.println(this.purpose.name());
            current.stackPointer = Pointer.zero();
            this.purpose = null;
            FatalError.unexpected("Stack walker already in use");
        }
    }

    /**
     * Gets an address corresponding to a native function call in a {@linkplain NativeStubGenerator native stub}.
     * If the native function call is found, then the address returned is actually one byte past the start of the
     * call instruction. This makes it consistent with walking up through a call in general where the instruction
     * pointer obtained for a caller's frame is actually the return address which is always greater than the
     * address of the call instruction itself.
     *
     * @param instructionPointer the instruction pointer in a native stub as saved by {@link NativeCallPrologue} or
     *            {@link NativeCallPrologueForC}
     * @param fatalIfNotFound specifies whether a {@linkplain FatalError fatal error} should be raised if the native
     *            stub has no native call just after {@code instructionPointer}.
     *            If this value is false and the search fails, then {@code instructionPointer} is returned.
     * @return the address of the second byte of the native function call after {@code instructionPointer} or zero if no such call exists
     */
    private Pointer getNativeFunctionCallInstructionPointerInNativeStub(Pointer instructionPointer, boolean fatalIfNotFound) {
        final CPSTargetMethod nativeStubTargetMethod = (CPSTargetMethod) targetMethodFor(instructionPointer);
        if (nativeStubTargetMethod != null) {
            final int targetCodePosition = nativeStubTargetMethod.targetCodePositionFor(instructionPointer);
            final int nativeFunctionCallPosition = nativeStubTargetMethod.findNextCall(targetCodePosition, true);
            final Pointer nativeFunctionCall = nativeFunctionCallPosition < 0 ? Pointer.zero() : nativeStubTargetMethod.codeStart().plus(nativeFunctionCallPosition);
            if (!nativeFunctionCall.isZero()) {
                // The returned instruction pointer must be one past the actual address of the
                // native function call. This makes it match the pattern expected by the
                // StackReferenceMapPreparer where the instruction pointer in all but the
                // top frame is past the address of the call.
                if (TRACE_STACK_WALK.getValue() && purpose == Purpose.REFERENCE_MAP_PREPARING) {
                    Log.print("IP for stack frame preparation of stub for native method ");
                    Log.print(nativeStubTargetMethod.name());
                    Log.print(" [");
                    Log.print(nativeStubTargetMethod.codeStart());
                    Log.print("+");
                    Log.print(nativeFunctionCallPosition + 1);
                    Log.println(']');
                }
                return nativeFunctionCall.plus(1);
            }
        }
        if (fatalIfNotFound) {
            if (nativeStubTargetMethod == null) {
                Log.print("Could not find native stub for instruction pointer ");
                Log.println(instructionPointer);
            } else {
                Log.print("Could not find native function call after ");
                Log.print(nativeStubTargetMethod.codeStart());
                Log.print("+");
                Log.print(instructionPointer.minus(nativeStubTargetMethod.codeStart()).toLong());
                Log.print(" in ");
                if (nativeStubTargetMethod.classMethodActor() != null) {
                    Log.printMethod(nativeStubTargetMethod.classMethodActor(), true);
                } else {
                    Log.println("<no method actor>");
                }
            }
            throw FatalError.unexpected("Could not find native function call in native stub");
        }
        return instructionPointer;
    }

    /**
     * Walks a thread's stack for the purpose of inspecting one or more frames on the stack. This method takes care of
     * {@linkplain #reset() resetting} this walker before returning.
     */
    public final void inspect(Pointer instructionPointer, Pointer stackPointer, Pointer framePointer, final StackFrameVisitor visitor) {
        // Wraps the visit operation to record the visited frame as the parent of the next frame to be visited.
        final StackFrameVisitor wrapper = new StackFrameVisitor() {
            public boolean visitFrame(StackFrame stackFrame) {
                if (calleeStackFrame == null || !stackFrame.isSameFrame(calleeStackFrame)) {
                    calleeStackFrame = stackFrame;
                } else {
                    Log.println("Same frame being visited twice: " + stackFrame);
                }
                return visitor.visitFrame(stackFrame);
            }
        };
        walk(instructionPointer, stackPointer, framePointer, INSPECTING, wrapper);
        calleeStackFrame = null;
        reset();
    }

    /**
     * Walks a thread's stack for the purpose of inspecting one or more frames on the stack. This method takes care of
     * {@linkplain #reset() resetting} this walker before returning.
     */
    public final void inspect(Pointer instructionPointer, Pointer stackPointer, Pointer framePointer, final RawStackFrameVisitor visitor) {
        walk(instructionPointer, stackPointer, framePointer, RAW_INSPECTING, visitor);
        calleeStackFrame = null;
        reset();
    }

    /**
     * Walks a thread's stack for the purpose of raising an exception.
     */
    public final void unwind(Pointer instructionPointer, Pointer stackPointer, Pointer framePointer, Throwable throwable) {
        walk(instructionPointer, stackPointer, framePointer, EXCEPTION_HANDLING, new StackUnwindingContext(stackPointer, throwable));
    }

    /**
     * Walks a thread's stack for the purpose of preparing the reference map of a thread's stack. This method takes care of
     * {@linkplain #reset() resetting} this walker before returning.
     */
    public final void prepareReferenceMap(Pointer instructionPointer, Pointer stackPointer, Pointer framePointer, StackReferenceMapPreparer preparer) {
        walk(instructionPointer, stackPointer, framePointer, REFERENCE_MAP_PREPARING, preparer);
        reset();
    }

    /**
     * Walks a thread's stack for the purpose of preparing the reference map of a thread's stack. This method takes care of
     * {@linkplain #reset() resetting} this walker before returning.
     */
    public final void verifyReferenceMap(Pointer instructionPointer, Pointer stackPointer, Pointer framePointer, StackReferenceMapPreparer preparer) {
        walk(instructionPointer, stackPointer, framePointer, REFERENCE_MAP_PREPARING, preparer);
        reset();
    }

    /**
     * Terminates the current stack walk.
     */
    @INLINE
    public final void reset() {
        if (TRACE_STACK_WALK.getValue()) {
            Log.print("StackFrameWalk: Finish stack frame walk for purpose ");
            Log.println(purpose);
        }

        current.reset();
        callee.reset();
        trapState = Pointer.zero();
        purpose = null;
    }

    /**
     * Gets the last stack frame {@linkplain StackFrameVisitor#visitFrame(StackFrame) visited} by this stack walker
     * while {@linkplain #inspect(Pointer, Pointer, Pointer, StackFrameVisitor) inspecting}. The returned frame is
     * the callee frame of the next frame to be visited.
     */
    public final StackFrame calleeStackFrame() {
        return calleeStackFrame;
    }

    public abstract TargetMethod targetMethodFor(Pointer instructionPointer);

    /**
     * Determines if this stack walker is currently in use. This is useful for detecting if an exception is being thrown as part of exception handling.
     */
    public final boolean isInUse() {
        return !current.stackPointer.isZero();
    }

    @INLINE
    public final Pointer stackPointer() {
        return current.stackPointer;
    }

    public final void advance(Word instructionPointer, Word stackPointer, Word framePointer) {
        callee.copyFrom(current);
        current.advance(instructionPointer.asPointer(), stackPointer.asPointer(), framePointer.asPointer());
    }

    @INLINE
    public final Pointer framePointer() {
        return current.framePointer;
    }

    @INLINE
    public final Pointer instructionPointer() {
        return current.instructionPointer;
    }

    /**
     * Records the trap state when walking a trap frame. This information can be subsequently {@linkplain #trapState()
     * accessed} when walking the frame in which the trap occurred.
     *
     * @param trapState the state pertinent to a trap
     */
    public void setTrapState(Pointer trapState) {
        this.trapState = trapState;
    }

    /**
     * Gets the state stored in the trap frame just below the frame currently being walked (i.e. the frame in which the
     * trap occurred).
     *
     * @return {@link Pointer#zero()} if the current frame is not a frame in which a trap occurred
     */
    public Pointer trapState() {
        return trapState;
    }

    /**
     * Collects a sequence of stack frames, beginning a stack walk at the specified instruction pointer, stack pointer,
     * and frame pointer. This method will return all stack frames, including native frames, adapter frames, and
     * non-application visible stack frames. This method accepts an appendable sequence of stack frames in
     * which to store the result.
     *
     * @param stackFrames an appendable sequence of stack frames to collect the results; if {@code null}, this method
     * will create a new appendable sequence for collecting the result
     * @param instructionPointer the instruction pointer from which to begin the stack walk
     * @param stackPointer the stack pointer from which to begin the stack walk
     * @param framePointer the frame pointer from which to begin the stack walk
     * @return a sequence of all the stack frames, including native, adapter, and non-application visible stack frames,
     *         with the top frame as the first frame
     */
    public Sequence<StackFrame> frames(AppendableSequence<StackFrame> stackFrames, Pointer instructionPointer, Pointer stackPointer, Pointer framePointer) {
        final AppendableSequence<StackFrame> frames = stackFrames == null ? new LinkSequence<StackFrame>() : stackFrames;
        final StackFrameVisitor visitor = new StackFrameVisitor() {
            public boolean visitFrame(StackFrame stackFrame) {
                frames.append(stackFrame);
                return true;
            }
        };
        inspect(instructionPointer, stackPointer, framePointer, visitor);
        return frames;
    }

    /**
     * Extracts a sequence of class method actors from a sequence of stack frames. It accepts a number of options that
     * indicate whether to include the top frame, adapter frames, native frames, and other frames that should not be
     * application visible.
     *
     * @param stackFrames an iterable list of stack frames
     * @param topFrame true if this method should include the ClassMethodActor of the top frame
     * @param adapterFrames true if adapter frames should be reported
     * @param invisibleFrames true if application invisible frames should be reported
     * @param ignoreUntilNativeFrame true if all frames before the first native frame are to be ignored
     * @return a sequence of class method actors representing the call stack
     */
    public static Sequence<ClassMethodActor> extractClassMethodActors(Iterable<StackFrame> stackFrames, boolean topFrame, boolean adapterFrames, boolean invisibleFrames, boolean ignoreUntilNativeFrame) {
        final LinkSequence<ClassMethodActor> result = new LinkSequence<ClassMethodActor>();
        boolean top = true;
        boolean seenNativeFrame = false;
        for (StackFrame stackFrame : stackFrames) {
            if (top) {
                top = false;
                if (!topFrame) {
                    continue;
                }
            }
            if (stackFrame instanceof AdapterStackFrame && !adapterFrames) {
                continue;
            }
            final TargetMethod targetMethod = Code.codePointerToTargetMethod(stackFrame.instructionPointer);
            if (targetMethod == null) {
                // native frame
                if (ignoreUntilNativeFrame && !seenNativeFrame) {
                    result.clear();
                    seenNativeFrame = true;
                }
                continue;
            }
            final Iterator<? extends BytecodeLocation> bytecodeLocations = targetMethod.getBytecodeLocationsFor(stackFrame.instructionPointer);
            if (bytecodeLocations == null) {
                if (targetMethod.classMethodActor() != null) {
                    appendClassMethodActor(result, targetMethod.classMethodActor(), invisibleFrames);
                }
            } else {
                appendCallers(result, bytecodeLocations, invisibleFrames);
            }
        }
        return result;
    }

    public static ClassMethodActor getCallerClassMethodActor(Iterable<StackFrame> stackFrames, boolean invisibleFrames) {
        boolean top = true;
        for (StackFrame stackFrame : stackFrames) {
            if (top) {
                top = false;
                continue;
            }
            final TargetMethod targetMethod = Code.codePointerToTargetMethod(stackFrame.instructionPointer);
            if (targetMethod == null) {
                // ignore native frame
                continue;
            }
            final Iterator<? extends BytecodeLocation> bytecodeLocations = targetMethod.getBytecodeLocationsFor(stackFrame.instructionPointer);
            if (bytecodeLocations == null) {
                if (targetMethod.classMethodActor() != null) {
                    return targetMethod.classMethodActor();
                }
            } else {
                return bytecodeLocations.next().classMethodActor;
            }
        }
        return null;
    }

    private static void appendCallers(AppendableSequence<ClassMethodActor> result, Iterator<? extends BytecodeLocation> bytecodeLocations, boolean invisibleFrames) {
        // this recursive method appends inlined bytecode locations to the frame list (i.e. parent first)
        if (bytecodeLocations.hasNext()) {
            final BytecodeLocation bytecodeLocation = bytecodeLocations.next();
            appendCallers(result, bytecodeLocations, invisibleFrames);
            appendClassMethodActor(result, bytecodeLocation.classMethodActor, invisibleFrames);
        }
    }

    private static void appendClassMethodActor(final AppendableSequence<ClassMethodActor> result, final ClassMethodActor classMethodActor, boolean invisibleFrames) {
        if (classMethodActor.isApplicationVisible() || invisibleFrames) {
            result.append(classMethodActor);
        }
    }

    public abstract Word readWord(Address address, int offset);
    public abstract byte readByte(Address address, int offset);
    public abstract int readInt(Address address, int offset);

    public abstract Word readRegister(Role role, TargetABI targetABI);

    /**
     * Reads the value of a given VM thread local from the safepoint-enabled thread locals.
     *
     * @param local the VM thread local to read
     * @return the value (as a word) of {@code local} in the safepoint-enabled thread locals
     */
    public abstract Word readWord(VmThreadLocal local);

    /**
     * Reads the value of a given VM thread local from the safepoint-enabled thread locals.
     *
     * @param local the VM thread local to read
     * @return the value (as a pointer) of {@code local} in the safepoint-enabled thread locals
     */
    public Pointer readPointer(VmThreadLocal local) {
        return readWord(local).asPointer();
    }

    /**
     * Updates the stack walker's frame and stack pointers with those specified by the target ABI (use the ABI stack and frame pointers).
     * This may be necessary when initiating stack walking: by default the stack frame walker uses the stack and frame pointers defined by the CPU.
     * This is incorrect when the ABI pointers differs from the CPU pointers (like it is the case with some JIT implementation, currently).
     * @param targetABI
     */
    public abstract void useABI(TargetABI targetABI);

}
