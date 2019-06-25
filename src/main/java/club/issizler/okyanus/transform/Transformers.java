package club.issizler.okyanus.transform;

import javassist.CannotCompileException;
import javassist.CtClass;
import javassist.NotFoundException;
import org.apache.logging.log4j.LogManager;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class Transformers {

	private static Map<String, Transformer> transformerMap = new HashMap<String, Transformer>();

	public static boolean shouldTransform(String name) {
		return transformerMap.containsKey(name);
	}

	public static byte[] transform(String name, CtClass c) throws IOException, CannotCompileException, NotFoundException {
		return transformerMap.get(name).transform(c);
	}

	public static void add(String clazz, String transformer) {
		LogManager.getLogger().info("Added transformer for " + clazz + ": " + transformer);

		try {
			Class transClass = Class.forName(transformer);

			if (!Transformer.class.isAssignableFrom(transClass)) {
				throw new RuntimeException("Transformer class does not implement Transformer!");
			}

			transformerMap.put(clazz, (Transformer) transClass.newInstance());
		} catch (ClassNotFoundException | IllegalAccessException | InstantiationException e) {
			e.printStackTrace();
			System.exit(-1);
		}
	}
}
