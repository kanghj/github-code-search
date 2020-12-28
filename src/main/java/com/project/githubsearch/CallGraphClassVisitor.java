package com.project.githubsearch;


import org.apache.bcel.classfile.Constant;
import org.apache.bcel.classfile.ConstantPool;
import org.apache.bcel.classfile.EmptyVisitor;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.ConstantPoolGen;
import org.apache.bcel.generic.MethodGen;

import org.apache.bcel.generic.*;

import java.util.ArrayList;
import java.util.List;

/**
 * The simplest of class visitors, invokes the method visitor class for each
 * method found.
 */
public class CallGraphClassVisitor extends EmptyVisitor {

    private List<String> methods = new ArrayList<>();

    private String clazzName;
    
    public CallGraphClassVisitor(JavaClass jc) {
        clazzName = jc.getClassName();

    	Method[] methods = jc.getMethods();
        for (int i = 0; i < methods.length; i++) {
            Method method = methods[i];
            method.accept(this);
        }
        

    }
    
    private String argumentList(Type[] arguments) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arguments.length; i++) {
            if (i != 0) {
                sb.append(",");
            }
            sb.append(arguments[i].toString());
        }
        return sb.toString();
    }

    @Override
    public void visitMethod(Method method) {
    	
    	if (clazzName != null) {
    		methods.add(clazzName + ":" + method.getName() + "(" + argumentList(method.getArgumentTypes()) + ")");
    	} else {
    		methods.add(method.getName() + "(" + argumentList(method.getArgumentTypes()) + ")");
    	}
    }


    public List<String> getMethods() {
        return this.methods;
    }
}