/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.devtools.j2objc.gen;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.j2objc.ast.*;
import com.google.devtools.j2objc.javac.JavacEnvironment;
import com.google.devtools.j2objc.util.ElementUtil;
import com.google.devtools.j2objc.util.NameTable;
import com.google.devtools.j2objc.util.TranslationUtil;
import com.google.devtools.j2objc.util.TypeUtil;
import com.google.j2objc.annotations.Property;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

/**
 * Generates implementation code for an AbstractTypeDeclaration node.
 *
 * @author Tom Ball, Keith Stanger
 */
public class TypeImplementationGenerator extends TypeGenerator {

  private static final ImmutableSet<String> NSNUMBER_DESIGNATED_INITIALIZERS = ImmutableSet.of(
      "initWithBool:",
      "initWithChar:",
      "initWithDouble:",
      "initWithFloat:",
      "initWithInt:",
      "initWithInteger:",
      "initWithLong:",
      "initWithLongLong:",
      "initWithShort:",
      "initWithUnsignedChar:",
      "initWithUnsignedInt:",
      "initWithUnsignedInteger:",
      "initWithUnsignedLong:",
      "initWithUnsignedLongLong:",
      "initWithUnsignedShort:");

  private TypeImplementationGenerator(SourceBuilder builder, AbstractTypeDeclaration node) {
    super(builder, node);
  }

  public static void generate(SourceBuilder builder, AbstractTypeDeclaration node) {
    new TypeImplementationGenerator(builder, node).generate();
  }

  private static Path toNormalizedSourcePath(String pathString) {
    return Paths.get(pathString).normalize().toAbsolutePath();
  }

  private String getSourceFilePath() {
    String sourceFilePathString = compilationUnit.getSourceFilePath();
    if (!options.emitRelativeLineDirectives()) {
      return sourceFilePathString;
    }

    Path sourceFilePath = toNormalizedSourcePath(sourceFilePathString);
    Path cwdPath = toNormalizedSourcePath(".");
    if (!sourceFilePath.startsWith(cwdPath)) {
      return sourceFilePathString;
    }

    return cwdPath.relativize(sourceFilePath).toString();
  }

  protected void generate() {
    if (typeNode.isDeadClass()) {
      newline();
      printStaticVars();
      return;
    }

    syncFilename(getSourceFilePath());

    //printInitFlagDefinition();
    printStaticVars();
    printEnumValuesArray();

    if (!super.isInterfaceType() || needsCompanionClass()) {
      newline();
      syncLineNumbers(typeNode.getName()); // avoid doc-comment
      printf("@implementation %s\n", typeName);
      printProperties();
      printStaticAccessors();
      printInnerDeclarations();
      printInitializeMethod();
      println("\n@end");
    }

    printOuterDeclarations();
    printTypeLiteralImplementation();
    printNameMapping();
  }

  private void printInitFlagDefinition() {
    //if (hasInitializeMethod()) {
      printf("\nJ2OBJC_INITIALIZED_DEFN(%s)\n", typeName);
    //}
  }

  private static final Predicate<VariableDeclarationFragment> PROPERTIES =
      new Predicate<VariableDeclarationFragment>() {
    @Override
    public boolean apply(VariableDeclarationFragment fragment) {
      VariableElement varElement = fragment.getVariableElement();
      return ElementUtil.hasAnnotation(varElement, Property.class)
          && !ElementUtil.isStatic(varElement);
    }
  };

  private void printProperties() {
    Iterable<VariableDeclarationFragment> fields =
        Iterables.filter(getInstanceFields(), PROPERTIES);
    if (Iterables.isEmpty(fields)) {
      return;
    }
    newline();
    for (VariableDeclarationFragment fragment : fields) {
      VariableElement varElement = fragment.getVariableElement();
      String propertyName = nameTable.getVariableBaseName(varElement);
      String varName = nameTable.getVariableShortName(varElement);
      println("@synthesize " + propertyName + " = " + varName + ";");
    }
  }

  private static final Predicate<VariableDeclarationFragment> NEEDS_DEFINITION =
      new Predicate<VariableDeclarationFragment>() {
    @Override
    public boolean apply(VariableDeclarationFragment fragment) {
      return !ElementUtil.isPrimitiveConstant(fragment.getVariableElement())
          // Private static vars are defined in the private declaration.
          && (!((FieldDeclaration) fragment.getParent()).hasPrivateDeclaration()
        		  || ElementUtil.isStringConstant(fragment.getVariableElement()));
    }
  };

  private void printStaticVars() {
	  
    Iterable<VariableDeclarationFragment> fields =
        Iterables.filter(getStaticFields(), NEEDS_DEFINITION);
    if (Iterables.isEmpty(fields)) {
      return;
    }
    newline();
    StringBuilder sb = new StringBuilder();
    for (VariableDeclarationFragment fragment : fields) {
      VariableElement varElement = fragment.getVariableElement();
      Expression initializer = fragment.getInitializer();
      String name = nameTable.getVariableQualifiedName(varElement);
      String objcType = getDeclarationType(varElement);
      objcType += objcType.endsWith("*") ? "" : " ";
      if (initializer == null) {
          printf("%s%s;\n", objcType, name);
      } 
      else if (ElementUtil.isStringConstant(varElement)) {
          if (!((FieldDeclaration) fragment.getParent()).hasPrivateDeclaration()) {
        	  printf("%s%s;\n", objcType, name);
          }
          sb.append("  ").append(name).append(" = ")
          	.append(generateExpression(initializer)).append(";\n");
      }
      else {
          String cast = !varElement.asType().getKind().isPrimitive()
                  && ElementUtil.isVolatile(varElement) ? "(void *)" : "";
              printf("%s%s = %s%s;\n", objcType, name, cast, generateExpression(initializer));
      }
    }
    if (sb.length() > 0) {
    	printf("\n__attribute__((constructor)) static void %s_init_string_constants() {\n", this.typeName);
    	print(sb.toString());
    	print("}\n");
    }
  }

  /**
   * Prints the list of static variable and/or enum constant accessor methods.
   */
  protected void printStaticAccessors() {
    if (!options.staticAccessorMethods()) {
      return;
    }
    for (VariableDeclarationFragment fragment : getStaticFields()) {
      if (!((FieldDeclaration) fragment.getParent()).hasPrivateDeclaration()) {
        VariableElement varElement = fragment.getVariableElement();
        TypeMirror type = varElement.asType();
        boolean isVolatile = ElementUtil.isVolatile(varElement);
        boolean isPrimitive = type.getKind().isPrimitive();
        String accessorName = nameTable.getStaticAccessorName(varElement);
        String varName = nameTable.getVariableQualifiedName(varElement);
        String objcType = nameTable.getObjCType(type);
        String typeSuffix = isPrimitive ? NameTable.capitalize(TypeUtil.getName(type)) : "Id";
        TypeElement declaringClass = ElementUtil.getDeclaringClass(varElement);
        String baseName = nameTable.getVariableBaseName(varElement);
        ExecutableElement getter =
            ElementUtil.findGetterMethod(baseName, type, declaringClass, /* isStatic = */ true);
        if (getter == null) {
          if (isVolatile) {
            printf(
                "\n+ (%s)%s {\n  return JreLoadVolatile%s(&%s);\n}\n",
                objcType, accessorName, typeSuffix, varName);
          } else {
            printf("\n+ (%s)%s {\n  return %s;\n}\n", objcType, accessorName, varName);
          }
        }
        ExecutableElement setter =
            ElementUtil.findSetterMethod(baseName, type, declaringClass, /* isStatic = */ true);
        if (setter == null && !ElementUtil.isFinal(varElement)) {
          String setterFunc = isVolatile
              ? (isPrimitive ? "JreAssignVolatile" + typeSuffix : (ElementUtil.isStatic(varElement) ? "JreVolatileStaticAssign" : "JreVolatileStrongAssign"))
              : ((isPrimitive | !options.useReferenceCounting()) ? null : "JreStaticAssign");
          if (setterFunc == null) {
            printf("\n+ (void)set%s:(%s)value {\n  %s = value;\n}\n",
                NameTable.capitalize(accessorName), objcType, varName);
          } else {
            printf("\n+ (void)set%s:(%s)value {\n  %s(&%s, value);\n}\n",
                NameTable.capitalize(accessorName), objcType, setterFunc, varName);
          }
        }
      }
    }
    if (typeNode instanceof EnumDeclaration) {
      for (EnumConstantDeclaration constant : ((EnumDeclaration) typeNode).getEnumConstants()) {
        VariableElement varElement = constant.getVariableElement();
        printf("\n+ (%s *)%s {\n  return %s;\n}\n",
            typeName, nameTable.getStaticAccessorName(varElement),
            nameTable.getVariableQualifiedName(varElement));
      }
    }
  }

  private void printEnumValuesArray() {
    if (typeNode instanceof EnumDeclaration) {
      List<EnumConstantDeclaration> constants = ((EnumDeclaration) typeNode).getEnumConstants();
      newline();
      printf("%s *%s_values_[%s];\n", typeName, typeName, constants.size());
    }
  }

  private void printTypeLiteralImplementation() {
    if (needsTypeLiteral() || needsClassInit()) {
      newline();
      if (needsClassInit()) {
    	 printf("J2OBJC_CLASS_INITIALIZE_SOURCE(%s)\n", typeName);
      }
      if (needsTypeLiteral()) {
     	 printf("J2OBJC_%s_TYPE_LITERAL_SOURCE(%s)\n",
     	   isInterfaceType() ? "INTERFACE" : "CLASS", typeName);
      }
    }
  }

  private void printNameMapping() {
    if (!options.stripNameMapping()) {
      Optional<String> mapping = nameTable.getNameMapping(typeElement, typeName);
      if (mapping.isPresent()) {
        newline();
        printf(mapping.get());
      }
    }
  }

  private boolean isDesignatedInitializer(ExecutableElement method) {
    if (!ElementUtil.isConstructor(method)) {
      return false;
    }
    String selector = nameTable.getMethodSelector(method);
    return selector.equals("init")
        || (typeUtil.isObjcSubtype(ElementUtil.getDeclaringClass(method), TypeUtil.NS_NUMBER)
            && NSNUMBER_DESIGNATED_INITIALIZERS.contains(selector));
  }

  @Override
  protected void printMethodDeclaration(MethodDeclaration m) {
    if (Modifier.isAbstract(m.getModifiers())) {
      return;
    }

    newline();
    boolean isDesignatedInitializer = isDesignatedInitializer(m.getExecutableElement());
    if (isDesignatedInitializer) {
      println("J2OBJC_IGNORE_DESIGNATED_BEGIN");
    }
    syncLineNumbers(m);  // avoid doc-comment
    print(getMethodSignature(m) + " ");
    String body = generateStatement(m.getBody());
    if (options.generateIOSTest() && CompilationUnit.isTestClass(super.typeElement.asType())) {
    	if (m.isTestClassSetup()) {
    	      String clazz = nameTable.getFullName(typeNode.getTypeElement());
    		
    	    List<Statement> initStatements = typeNode.getClassInitStatements();
    	    String name = m.getName().toString();
    	    initStatements.add(new NativeStatement("[" + clazz + " " + name + "];"));
    	}
    }
    print(reindent(body) + "\n");
    if (isDesignatedInitializer) {
      println("J2OBJC_IGNORE_DESIGNATED_END");
    }
  }

  // for -Xconst-ref-args
  protected String getMutableParameters(FunctionDeclaration function) {
    StringBuilder sb = null;
    for (Iterator<SingleVariableDeclaration> iter = function.getParameters().iterator(); iter.hasNext(); ) {
      SingleVariableDeclaration var = iter.next();
      if (var.isMutable()) {
        String paramType = nameTable.getObjCType(var.getVariableElement().asType());
        boolean isObject = paramType.endsWith("*") || "id".equals(paramType) || paramType.startsWith("id<");
        if (isObject) {
          if (sb == null) {
            sb = new StringBuilder();
          }
          String name = nameTable.getVariableShortName(var.getVariableElement());
          sb.append("  ").append(paramType).append(' ').append(name).append(" = ")
          .append(name).append("_0;").append('\n');
        }
      }
    }
    return sb == null ? "" : sb.toString();
 }
 
  
  @Override
  protected void printFunctionDeclaration(FunctionDeclaration function) {
    newline();
    syncLineNumbers(function);  // avoid doc-comment
    if (Modifier.isNative(function.getModifiers())) {
      printJniFunctionAndWrapper(function);
    } else if (options.enableConstRefArgs()) {
      String functionBody = generateStatement(function.getBody());
      String sig = getFunctionSignature(function, false);
      String mutableParams = getMutableParameters(function);
      if (mutableParams.length() > 0) {
    	  functionBody = "{\n" + mutableParams + "\n@autoreleasepool " + functionBody + "}";
      }
      println(sig + ' ' + reindent(functionBody));
    }
    else {
      String functionBody = generateStatement(function.getBody());
      println(getFunctionSignature(function, false) + " " + reindent(functionBody));
    }
  }

  private String getJniFunctionSignature(FunctionDeclaration function) {
    StringBuilder sb = new StringBuilder();
    sb.append(nameTable.getJniType(function.getReturnType().getTypeMirror()));
    sb.append(' ');
    sb.append(function.getJniSignature()).append('(');
    sb.append("JNIEnv *_env_");
    if (Modifier.isStatic(function.getModifiers())) {
      sb.append(", jclass _cls_");
    }
    if (!function.getParameters().isEmpty()) {
      sb.append(", ");
    }
    for (Iterator<SingleVariableDeclaration> iter = function.getParameters().iterator();
         iter.hasNext(); ) {
      VariableElement var = iter.next().getVariableElement();
      String paramType = nameTable.getJniType(var.asType());
      sb.append(paramType + ' ' + nameTable.getVariableBaseName(var));
      if (iter.hasNext()) {
        sb.append(", ");
      }
    }
    sb.append(')');
    return sb.toString();
  }

  private void printJniFunctionAndWrapper(FunctionDeclaration function) {
    // Declare the matching JNI function.
    print("JNIEXPORT ");
    print(getJniFunctionSignature(function));
    println(";\n");

    // Generate a wrapper function that calls the matching JNI function.
    print(getFunctionSignature(function, false));
    println(" {");
    print("  ");
    TypeMirror returnType = function.getReturnType().getTypeMirror();
    if (!TypeUtil.isVoid(returnType)) {
      if (returnType.getKind().isPrimitive()) {
        print("return ");
      } else {
        printf("return (%s) ", nameTable.getObjCType(returnType));
      }
    }
    print(function.getJniSignature());
    print("(&J2ObjC_JNIEnv");
    if (Modifier.isStatic(function.getModifiers())) {
      printf(", %s_class_()", typeName);
    }
    for (SingleVariableDeclaration param : function.getParameters()) {
      printf(", %s", nameTable.getVariableBaseName(param.getVariableElement()));
    }
    println(");");
    println("}");
  }

  @Override
  protected void printNativeDeclaration(NativeDeclaration declaration) {
    String code = declaration.getImplementationCode();
    if (code != null) {
      newline();
      println(reindent(code));
    }
  }

  private void printInitializeMethod() {
    List<Statement> initStatements = typeNode.getClassInitStatements();
    List<TypeElement> interfaces = TranslationUtil.getInterfaceTypes(typeNode);
    if (!super.needsClassInit()) {
    	return;
    }
    StringBuilder sb = new StringBuilder();
    sb.append("{\n");
    
    if (!TypeUtil.isAnnotation(this.typeElement.asType())) {
	    String superType = super.getSuperTypeName();
	    sb.append(superType + "_initialize();\n");
	    
	    // init super classes.
	    for (TypeElement intrface : interfaces) {
	    	if (intrface == JavacEnvironment.unreachbleError
	    	||  intrface.getQualifiedName().toString().startsWith("com.google.j2objc.")) {
	    		continue;
	    	}
	      	sb.append(nameTable.getFullName(intrface) + "_initialize();\n");
	    }
    }
    
    for (Statement statement : initStatements) {
      sb.append(generateStatement(statement));
    }
    sb.append("}");
    print("\nstatic void " + typeName + "__clinit__() " + reindent(sb.toString()) + "\n");
    
  }

  protected String generateStatement(Statement stmt) {
    return StatementGenerator.generate(stmt, getBuilder().getCurrentLine());
  }

  @Override
  protected String nullability(Element element) {
    return "";
  }
}
