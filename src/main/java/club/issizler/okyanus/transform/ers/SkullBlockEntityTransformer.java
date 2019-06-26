// https://github.com/PaperMC/Paper/blob/ver/1.14/Spigot-Server-Patches/0091-Don-t-tick-Skulls-unused-code.patch

package club.issizler.okyanus.transform.ers;

import club.issizler.okyanus.transform.Transformer;
import club.issizler.okyanus.transform.TransformerToolkit;
import javassist.CannotCompileException;
import javassist.CtClass;
import javassist.NotFoundException;
import javassist.bytecode.AnnotationsAttribute;

import java.io.IOException;

public class SkullBlockEntityTransformer implements Transformer {

    @Override
    public byte[] transform(CtClass c) throws IOException, CannotCompileException, NotFoundException {
        TransformerToolkit.removeInterface(c, "net.minecraft.class_3000");
        ((AnnotationsAttribute) c.getDeclaredMethod("method_16896").getMethodInfo().getAttribute(AnnotationsAttribute.visibleTag)).removeAnnotation(Override.class.getName());

        return c.toBytecode();
    }

}
