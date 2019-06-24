package club.issizler.okyanus.transform;

import javassist.CannotCompileException;
import javassist.CtClass;
import javassist.NotFoundException;

import java.io.IOException;

public interface Transformer {
	byte[] transform(CtClass c) throws IOException, CannotCompileException, NotFoundException;
}
