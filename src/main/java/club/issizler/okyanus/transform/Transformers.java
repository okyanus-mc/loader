package club.issizler.okyanus.transform;

import javassist.CannotCompileException;
import javassist.CtClass;
import javassist.NotFoundException;
import org.apache.logging.log4j.LogManager;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class Transformers {

    private static Map<String, String> transformerMap = new HashMap<>();

    public static boolean shouldTransform(String name) {
        return transformerMap.containsKey(name);
    }

    public static byte[] transform(String name, CtClass c) throws NotFoundException, CannotCompileException, IOException {
        try {
            String transformer = transformerMap.get(name);
            Class transClass = Class.forName(transformer);

            if (!Transformer.class.isAssignableFrom(transClass)) {
                throw new RuntimeException("Transformer class does not implement Transformer!");
            }

            return ((Transformer) transClass.newInstance()).transform(c);
        } catch (ClassNotFoundException | IllegalAccessException | InstantiationException e) {
            e.printStackTrace();
            System.exit(-1);
        }

        return null;
    }

    public static void add(String clazz, String transformer) {
        LogManager.getLogger().info("Added transformer for " + clazz + ": " + transformer);
        transformerMap.put(clazz, transformer);
    }
}
