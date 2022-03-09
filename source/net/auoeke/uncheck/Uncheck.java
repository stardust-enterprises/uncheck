package net.auoeke.uncheck;

import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import fr.stardustenterprises.deface.engine.NativeTransformationService;
import fr.stardustenterprises.deface.engine.api.IClassTransformer;
import fr.stardustenterprises.deface.engine.api.ITransformationService;
import lombok.SneakyThrows;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.function.Consumer;

public class Uncheck implements Plugin, Opcodes {
    @Override
    public String getName() {
        return "uncheck";
    }

    @Override
    public void init(JavacTask task, String... args) {
    }

    @Override
    public boolean autoStart() {
        return true;
    }

    @SneakyThrows
    private static void transform(ITransformationService defaceService, Class<?> target, Consumer<ClassNode> transformer) {
        IClassTransformer t = (type, loader, name, domain, bytes) -> {
            if (target != type) {
                return bytes;
            }

            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, 0);
            transformer.accept(node);
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
            node.accept(writer);

            return writer.toByteArray();
        };

        defaceService.addTransformers(t);
        defaceService.retransformClasses(target);
        defaceService.removeTransformers(t);
    }

    private static MethodNode method(ClassNode type, String name) {
        return type.methods.stream().filter(method -> method.name.equals(name)).findAny().orElse(null);
    }

    @SneakyThrows
    private static void disableFlowAndCaptureAnalysis(ITransformationService transformationService) {
        transform(transformationService, Class.forName("com.sun.tools.javac.comp.Flow"), node -> {
            MethodNode analyzeTree = method(node, "analyzeTree");
            InsnList instructions = analyzeTree.instructions;

            for (AbstractInsnNode instruction : instructions) {
                if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode method = (MethodInsnNode) instruction;
                    if (method.owner.matches(".+\\$(FlowAnalyzer|CaptureAnalyzer)$") && method.name.equals("analyzeTree")) {
                        instructions.remove(instruction);
                    }
                }
            }
        });
    }

    @SneakyThrows
    private static void allowNonConstructorFirstStatement(ITransformationService defaceService) {
        transform(defaceService, Class.forName("com.sun.tools.javac.comp.Attr"), node -> {
            MethodNode checkFirstConstructorStat = method(node, "checkFirstConstructorStat");

            for (AbstractInsnNode instruction : checkFirstConstructorStat.instructions) {
                if (instruction instanceof VarInsnNode && ((VarInsnNode)instruction).var == 3) {
                    ((JumpInsnNode) instruction.getNext()).setOpcode(GOTO);

                    break;
                }
            }
        });
    }

    static {
        ITransformationService defaceService = NativeTransformationService.INSTANCE;
        disableFlowAndCaptureAnalysis(defaceService);
        allowNonConstructorFirstStatement(defaceService);
    }
}
