package com.skcraft.launcher.agent.window;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

final class WindowClassTransformer implements ClassFileTransformer {

    static final String AWT_COMPONENT = "java/awt/Component";
    static final String LWJGL2_DISPLAY = "org/lwjgl/opengl/Display";
    static final String GLFW = "org/lwjgl/glfw/GLFW";

    private static final String APPLIED_FIELD = "skcraft$windowAgentApplied";
    private static final String AWT_HELPER_METHOD = "skcraft$applyMaximizedState";
    private static final String AWT_HELPER_DESC = "(Ljava/awt/Component;Z)V";
    private static final String GLFW_HELPER_METHOD = "skcraft$maximizeOnShow";
    private static final String GLFW_HELPER_DESC = "(J)V";
    private static final String GLFW_SHOW_WINDOW = "glfwShowWindow";
    private static final String GLFW_SHOW_WINDOW_DESC = "(J)V";
    private static final String GLFW_MAXIMIZE_WINDOW = "glfwMaximizeWindow";
    private static final String GLFW_MAXIMIZE_WINDOW_DESC = "(J)V";
    private static final String TRANSFORMED_FIELD = "skcraft$windowAgentTransformed";
    private static final int SYNTHETIC_STATIC = Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;

    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (classfileBuffer == null || className == null) {
            return null;
        }

        try {
            byte[] transformed;
            if (AWT_COMPONENT.equals(className)) {
                transformed = transformAwtComponent(classfileBuffer);
            } else if (LWJGL2_DISPLAY.equals(className)) {
                transformed = transformLwjgl2Display(classfileBuffer);
            } else if (GLFW.equals(className)) {
                transformed = transformGlfw(classfileBuffer);
            } else {
                return null;
            }

            if (transformed != null) {
                System.out.println("[SKCraft Window Agent] Installed " + className + " integration");
            } else {
                System.err.println("[SKCraft Window Agent] Unsupported " + className + " layout; leaving unchanged");
            }
            return transformed;
        } catch (Throwable t) {
            System.err.println("[SKCraft Window Agent] Failed to transform " + className + ": " + t);
            return null;
        }
    }

    static byte[] transformAwtComponent(byte[] original) {
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(reader, 0);
        AwtComponentVisitor visitor = new AwtComponentVisitor(writer);
        reader.accept(visitor, 0);
        return visitor.changed ? writer.toByteArray() : null;
    }

    static byte[] transformLwjgl2Display(byte[] original) {
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(reader, 0);
        Lwjgl2DisplayVisitor visitor = new Lwjgl2DisplayVisitor(writer);
        reader.accept(visitor, 0);
        return visitor.changed ? writer.toByteArray() : null;
    }

    static byte[] transformGlfw(byte[] original) {
        ClassReader reader = new ClassReader(original);
        GlfwLayout layout = new GlfwLayout();
        reader.accept(layout, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (!layout.canTransform()) {
            return null;
        }

        ClassWriter writer = new ClassWriter(reader, 0);
        GlfwVisitor visitor = new GlfwVisitor(writer);
        reader.accept(visitor, 0);
        return visitor.changed ? writer.toByteArray() : null;
    }

    private static final class GlfwLayout extends ClassVisitor {
        private boolean alreadyTransformed;
        private boolean hasJavaShowWindow;
        private boolean hasMaximizeWindow;

        private GlfwLayout() {
            super(Opcodes.ASM9);
        }

        private boolean canTransform() {
            return !alreadyTransformed && hasJavaShowWindow && hasMaximizeWindow;
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            if (TRANSFORMED_FIELD.equals(name)) {
                alreadyTransformed = true;
            }
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            if ((access & Opcodes.ACC_STATIC) == 0) {
                return null;
            }
            if (GLFW_SHOW_WINDOW.equals(name) && GLFW_SHOW_WINDOW_DESC.equals(descriptor)) {
                hasJavaShowWindow = (access & Opcodes.ACC_NATIVE) == 0;
            } else if (GLFW_MAXIMIZE_WINDOW.equals(name) && GLFW_MAXIMIZE_WINDOW_DESC.equals(descriptor)) {
                hasMaximizeWindow = true;
            }
            return null;
        }
    }

    private static final class AwtComponentVisitor extends ClassVisitor {
        private boolean changed;
        private boolean helperPresent;
        private boolean fieldPresent;

        private AwtComponentVisitor(ClassVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            if (APPLIED_FIELD.equals(name)) {
                fieldPresent = true;
            }
            return super.visitField(access, name, descriptor, signature, value);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (AWT_HELPER_METHOD.equals(name) && AWT_HELPER_DESC.equals(descriptor)) {
                helperPresent = true;
                return delegate;
            }
            if (!"setVisible".equals(name) || !"(Z)V".equals(descriptor)) {
                return delegate;
            }
            if (fieldPresent) {
                return delegate;
            }

            changed = true;
            return new MethodVisitor(Opcodes.ASM9, delegate) {
                @Override
                public void visitInsn(int opcode) {
                    if (opcode == Opcodes.RETURN) {
                        super.visitVarInsn(Opcodes.ALOAD, 0);
                        super.visitVarInsn(Opcodes.ILOAD, 1);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, AWT_COMPONENT, AWT_HELPER_METHOD, AWT_HELPER_DESC, false);
                    }
                    super.visitInsn(opcode);
                }

                @Override
                public void visitMaxs(int maxStack, int maxLocals) {
                    super.visitMaxs(Math.max(maxStack, 2), maxLocals);
                }
            };
        }

        @Override
        public void visitEnd() {
            if (changed && !fieldPresent) {
                addAppliedField(this);
            }
            if (changed && !helperPresent) {
                addAwtHelper();
            }
            super.visitEnd();
        }

        private void addAwtHelper() {
            MethodVisitor method = super.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, AWT_HELPER_METHOD, AWT_HELPER_DESC, null, null);
            method.visitCode();
            Label done = new Label();

            method.visitVarInsn(Opcodes.ILOAD, 1);
            method.visitJumpInsn(Opcodes.IFEQ, done);
            method.visitFieldInsn(Opcodes.GETSTATIC, AWT_COMPONENT, APPLIED_FIELD, "Z");
            method.visitJumpInsn(Opcodes.IFNE, done);
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitTypeInsn(Opcodes.INSTANCEOF, "java/awt/Frame");
            method.visitJumpInsn(Opcodes.IFEQ, done);
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitTypeInsn(Opcodes.CHECKCAST, "java/awt/Frame");
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/awt/Frame", "getOwner", "()Ljava/awt/Window;", false);
            method.visitJumpInsn(Opcodes.IFNONNULL, done);
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, AWT_COMPONENT, "getWidth", "()I", false);
            method.visitIntInsn(Opcodes.SIPUSH, 400);
            method.visitJumpInsn(Opcodes.IF_ICMPLT, done);
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, AWT_COMPONENT, "getHeight", "()I", false);
            method.visitIntInsn(Opcodes.SIPUSH, 300);
            method.visitJumpInsn(Opcodes.IF_ICMPLT, done);

            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitTypeInsn(Opcodes.CHECKCAST, "java/awt/Frame");
            method.visitInsn(Opcodes.DUP);
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/awt/Frame", "getExtendedState", "()I", false);
            method.visitIntInsn(Opcodes.BIPUSH, 6);
            method.visitInsn(Opcodes.IOR);
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/awt/Frame", "setExtendedState", "(I)V", false);
            method.visitInsn(Opcodes.ICONST_1);
            method.visitFieldInsn(Opcodes.PUTSTATIC, AWT_COMPONENT, APPLIED_FIELD, "Z");

            method.visitLabel(done);
            method.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
            method.visitInsn(Opcodes.RETURN);
            method.visitMaxs(3, 2);
            method.visitEnd();
        }
    }

    private static final class Lwjgl2DisplayVisitor extends ClassVisitor {
        private boolean changed;
        private boolean alreadyTransformed;

        private Lwjgl2DisplayVisitor(ClassVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            if (TRANSFORMED_FIELD.equals(name)) {
                alreadyTransformed = true;
            }
            return super.visitField(access, name, descriptor, signature, value);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (!"createWindow".equals(name) || !"()V".equals(descriptor) || (access & Opcodes.ACC_STATIC) == 0 || alreadyTransformed) {
                return delegate;
            }

            changed = true;
            return new MethodVisitor(Opcodes.ASM9, delegate) {
                @Override
                public void visitCode() {
                    super.visitCode();
                    super.visitLdcInsn(Type.getObjectType(LWJGL2_DISPLAY));
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "com/skcraft/launcher/agent/window/Lwjgl2Maximizer", "prepare", "(Ljava/lang/Class;)V", false);
                }

                @Override
                public void visitMaxs(int maxStack, int maxLocals) {
                    super.visitMaxs(Math.max(maxStack, 1), maxLocals);
                }
            };
        }

        @Override
        public void visitEnd() {
            if (changed) {
                addMarkerField(this);
            }
            super.visitEnd();
        }
    }

    private static final class GlfwVisitor extends ClassVisitor {
        private String owner;
        private boolean changed;
        private boolean helperPresent;
        private boolean appliedFieldPresent;

        private GlfwVisitor(ClassVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            if (APPLIED_FIELD.equals(name)) {
                appliedFieldPresent = true;
            }
            return super.visitField(access, name, descriptor, signature, value);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            owner = name;
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (GLFW_HELPER_METHOD.equals(name) && GLFW_HELPER_DESC.equals(descriptor)) {
                helperPresent = true;
                return delegate;
            }
            // Maximize after show so Windows uses SW_MAXIMIZE, not a WS_MAXIMIZE create-time fake.
            if (!GLFW_SHOW_WINDOW.equals(name) || !GLFW_SHOW_WINDOW_DESC.equals(descriptor) || (access & Opcodes.ACC_STATIC) == 0 || (access & Opcodes.ACC_NATIVE) != 0) {
                return delegate;
            }

            changed = true;
            return new MethodVisitor(Opcodes.ASM9, delegate) {
                @Override
                public void visitInsn(int opcode) {
                    if (opcode == Opcodes.RETURN) {
                        super.visitVarInsn(Opcodes.LLOAD, 0);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, owner, GLFW_HELPER_METHOD, GLFW_HELPER_DESC, false);
                    }
                    super.visitInsn(opcode);
                }

                @Override
                public void visitMaxs(int maxStack, int maxLocals) {
                    super.visitMaxs(Math.max(maxStack, 2), maxLocals);
                }
            };
        }

        @Override
        public void visitEnd() {
            if (changed && !appliedFieldPresent) {
                addAppliedField(this);
            }
            if (changed && !helperPresent) {
                addGlfwHelper();
            }
            if (changed) {
                addMarkerField(this);
            }
            super.visitEnd();
        }

        private void addGlfwHelper() {
            MethodVisitor method = super.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, GLFW_HELPER_METHOD, GLFW_HELPER_DESC, null, null);
            method.visitCode();
            Label done = new Label();

            method.visitVarInsn(Opcodes.LLOAD, 0);
            method.visitInsn(Opcodes.LCONST_0);
            method.visitInsn(Opcodes.LCMP);
            method.visitJumpInsn(Opcodes.IFEQ, done);
            method.visitFieldInsn(Opcodes.GETSTATIC, owner, APPLIED_FIELD, "Z");
            method.visitJumpInsn(Opcodes.IFNE, done);
            method.visitVarInsn(Opcodes.LLOAD, 0);
            method.visitMethodInsn(Opcodes.INVOKESTATIC, owner, GLFW_MAXIMIZE_WINDOW, GLFW_MAXIMIZE_WINDOW_DESC, false);
            method.visitInsn(Opcodes.ICONST_1);
            method.visitFieldInsn(Opcodes.PUTSTATIC, owner, APPLIED_FIELD, "Z");

            method.visitLabel(done);
            method.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
            method.visitInsn(Opcodes.RETURN);
            method.visitMaxs(4, 2);
            method.visitEnd();
        }
    }

    private static void addAppliedField(ClassVisitor visitor) {
        addBooleanField(visitor, SYNTHETIC_STATIC | Opcodes.ACC_VOLATILE, APPLIED_FIELD, null);
    }

    private static void addMarkerField(ClassVisitor visitor) {
        addBooleanField(visitor, SYNTHETIC_STATIC | Opcodes.ACC_FINAL, TRANSFORMED_FIELD, Integer.valueOf(1));
    }

    private static void addBooleanField(ClassVisitor visitor, int access, String name, Object value) {
        FieldVisitor field = visitor.visitField(access, name, "Z", null, value);
        if (field != null) {
            field.visitEnd();
        }
    }
}
