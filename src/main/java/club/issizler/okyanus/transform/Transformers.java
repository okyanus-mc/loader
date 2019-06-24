package club.issizler.okyanus.transform;

import javassist.CannotCompileException;
import javassist.CtClass;
import javassist.NotFoundException;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class Transformers {

	private static Map<String, Transformer> transformerMap = new HashMap<String, Transformer>() {{

	}};

	public static boolean shouldTransform(String name) {
		//System.out.println("shouldTransform called with [" + name + "]");

		return transformerMap.containsKey(name);
	}

	public static byte[] transform(String name, CtClass c) throws IOException, CannotCompileException, NotFoundException {
		System.out.println("transform called with [" + name + "]");

		return transformerMap.get(name).transform(c);
	}

}
