package com.amd.aparapi;

import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Created with IntelliJ IDEA.
 * User: gfrost
 * Date: 6/21/13
 * Time: 4:21 PM
 * To change this template use File | Settings | File Templates.
 */
public class LambdaKernelCall{
   protected static Logger logger = Logger.getLogger(Config.getLoggerName());
   private Aparapi.Lambda lambdaCallSite;
   private String lambdaMethodName;
   private Field[] lambdaCapturedFields;
   private Object lambdaThisObject;
   private String lambdaMethodSignature;
   private boolean isStatic;
   private boolean isObjectLambda;
   private Class lambdaKernelClass;

   public boolean isStatic(){
      return isStatic;
   }

   public boolean isObjectLambda(){
      return isObjectLambda;
   }

   public Object getLambdaKernelThis(){
      return isStatic == true?null:lambdaThisObject;
   }

   public String getLambdaMethodName(){
      return lambdaMethodName;
   }

   public String getLambdaMethodSignature(){
      return lambdaMethodSignature;
   }

   public Class getLambdaKernelClass(){
      return lambdaKernelClass;
   }

   public String toString(){
      return getLambdaKernelClass()+" "+getLambdaMethodName()+" "+
            getLambdaMethodSignature()+" from lambdaCallSite: "+ lambdaCallSite;
   }

   public Field[] getLambdaCapturedFields(){
      return lambdaCapturedFields;
   }

   // Collect 'this' object as first parameter to lambda from Consumer object
   void collectLambdaThis(Field bcf) throws AparapiException{
      try{
         Class currFieldType = bcf.getType();
         long offset = UnsafeWrapper.objectFieldOffset(bcf);
         if (currFieldType.isPrimitive() == false){
            lambdaThisObject = UnsafeWrapper.getObject(lambdaCallSite, offset);
         }else{
            if (logger.isLoggable(Level.WARNING)){
               logger.warning("# Problem getting Lambda this: "+currFieldType+"  "+bcf.getName());
            }
         }
         if (logger.isLoggable(Level.FINE)){
            logger.fine("# Lambda this: "+currFieldType+"  "+bcf.getName()+" = "+lambdaThisObject);
         }
      }catch (Exception e){
         System.out.println("Problem getting Block args:"+e);
         throw new AparapiException(e);
      }

   }

   public LambdaKernelCall(Aparapi.Lambda _lambdaCallSite) throws AparapiException, ClassNotFoundException{
      lambdaCallSite = _lambdaCallSite;

      Class lambdaCallSiteClass = lambdaCallSite.getClass();
      if (logger.isLoggable(Level.FINE)){
         logger.fine("CallSite class calling lambda = "+lambdaCallSiteClass);
      }

      // The class name is created with the "/" style delimiters
      ClassModel lambdaCallSiteClassModel = ClassModel.getClassModel(lambdaCallSiteClass);

      MethodModel acceptModel;
      // TODO: use a more maintainable method for determining entry method
      if (lambdaCallSite instanceof Aparapi.IntTerminal){
         acceptModel = lambdaCallSiteClassModel.getMethodModel("accept", "(I)V");
      }else if (lambdaCallSite instanceof Aparapi.Int2BooleanMapper){
         acceptModel = lambdaCallSiteClassModel.getMethodModel("map", "(I)Z");
      }else{
         // Calling an object Consumer lambda like: public void accept(java.lang.Object)
         acceptModel = lambdaCallSiteClassModel.getMethodModel("accept", "(Ljava/lang/Object;)V");
      }

      assert acceptModel != null:"acceptModel should not be null";
      // this is the set of methods that the clalsite calls.  Should only be one.
      Set<InstructionSet.MethodCall> acceptCallSites = acceptModel.getMethod().getMethodCalls();
      assert acceptCallSites.size() == 1:"Should only have one call site in this method";

      InstructionSet.MethodCall lambdaCallSite = acceptCallSites.iterator().next();
      ClassModel.ConstantPool.MethodEntry lambdaCallTarget = lambdaCallSite.getConstantPoolMethodEntry();

      String lambdaDotClassName = lambdaCallTarget.getClassEntry().getDotClassName();

      lambdaKernelClass = Class.forName(lambdaDotClassName, false, lambdaCallSiteClass.getClassLoader());
      lambdaMethodName = lambdaCallTarget.getNameAndTypeEntry().getNameUTF8Entry().getUTF8();
      lambdaMethodSignature = lambdaCallTarget.getNameAndTypeEntry().getDescriptorUTF8Entry().getUTF8();

      // The first field is "this" for the lambda call if the lambda
      // is not static, the later fields are captured values which will
      // become lambda call parameters

      Field[] allBlockClassFields = lambdaCallSiteClass.getDeclaredFields();

      if (logger.isLoggable(Level.FINE)){
         logger.fine("# allBlockClassFields.length: "+allBlockClassFields.length);
         for (Field f : allBlockClassFields){
            logger.fine("# Block obj field: "+f.getType().getName()+" "+f);
         }
      }
      Field[] capturedFieldsWithoutThis;
      if ((lambdaCallSite instanceof InstructionSet.VirtualMethodCall) == true){
         isStatic = false;

         capturedFieldsWithoutThis = new Field[allBlockClassFields.length-1];
         for (int i = 1; i<allBlockClassFields.length; i++){
            capturedFieldsWithoutThis[i-1] = allBlockClassFields[i];
         }

      }else{
         isStatic = true;

         capturedFieldsWithoutThis = new Field[allBlockClassFields.length];
         for (int i = 0; i<allBlockClassFields.length; i++){
            capturedFieldsWithoutThis[i] = allBlockClassFields[i];
         }
      }

      if (lambdaMethodSignature.endsWith("I)V")){
         // It is an int lambda
         isObjectLambda = false;
         if (logger.isLoggable(Level.FINE)){
            logger.fine("# Found int lambda");
         }

      }else if (lambdaMethodSignature.endsWith("I)Z")){
         // It is an int lambda
         isObjectLambda = false;
         if (logger.isLoggable(Level.FINE)){
            logger.fine("# Found boolean lambda");
         }
      }else if (lambdaMethodSignature.contains("L") && lambdaMethodSignature.endsWith(";)V")){
         // It is an object lambda
         isObjectLambda = true;
         if (logger.isLoggable(Level.FINE)){
            logger.fine("# Found Object lambda");
         }

      }

      if (logger.isLoggable(Level.FINE)){
         logger.fine("call target = "+lambdaDotClassName+
               " "+lambdaMethodName+" "+lambdaMethodSignature+" ## target lambda is static? "+isStatic+
               ", its loader = "+getLambdaKernelClass().getClassLoader());
      }

      if (!isStatic()){
         collectLambdaThis(allBlockClassFields[0]);
      }
      lambdaCapturedFields = capturedFieldsWithoutThis;

      lambdaKernelClassModel = ClassModel.getClassModel(lambdaKernelClass);
      lambdaKernelClassModelMethod = lambdaKernelClassModel.getMethod(lambdaMethodName, lambdaMethodSignature);

   }

   ClassModel lambdaKernelClassModel;

   ClassModel.ClassModelMethod lambdaKernelClassModelMethod;

   ClassModel.ClassModelMethod getLambdaKernelClassModelMethod(){
      return (lambdaKernelClassModelMethod);
   }

   Object unsafeGetFieldRefFromObject(Object sourceObj, String fieldName){
      // Get ref to java.util.stream.AbstractPipeline.source
      Object fieldRef = null;
      Field f = null;
      try{
         f = sourceObj.getClass().getDeclaredField(fieldName);
         Type t = f.getType();
         if (t.equals(float.class)){
            System.out.println("is float");
            // fieldRef = (float)f.getFloat(sourceObj);
         }

         long offset = UnsafeWrapper.objectFieldOffset(f);
         fieldRef = UnsafeWrapper.getObject(sourceObj, offset);
      }catch (NoSuchFieldException e){
         e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
      }

      return fieldRef;
   }

   // Get source array ref from Stream obj to set up as a kernel argument
   //
   // For the time being the only supported Stream is ReferencePipeline
   // with super class AbstractPipeline
   Object setupStreamSource(Stream _source) throws AparapiException{
      Class sourceSuperClass = _source.getClass().getSuperclass();

      if (logger.isLoggable(Level.FINE)){
         logger.fine("Stream source class= "+_source.getClass()+" super= "+sourceSuperClass);
      }

      Field[] streamClassFields = sourceSuperClass.getDeclaredFields();
      if (logger.isLoggable(Level.FINE)){
         logger.fine("# sourceClassFields.length: "+streamClassFields.length);
         for (Field f : streamClassFields){
            logger.fine("# source class field: "+f.getType().getName()+" "+f);
         }
      }

      // Get ref to java.util.stream.AbstractPipeline.source
      Object sourceObj = null;
      for (Field f : streamClassFields){
         if (f.getName().equals("source")){
            long offset = UnsafeWrapper.objectFieldOffset(f);
            sourceObj = UnsafeWrapper.getObject(_source, offset);
            break;
         }
      }

      Object argObj = unsafeGetFieldRefFromObject(sourceObj, "arg$1");
      // Get the elements out of the arg$1 ref
      Object elementsObj = unsafeGetFieldRefFromObject(argObj, "elements");

      if (logger.isLoggable(Level.FINE)){
         logger.fine("# elements array: "+elementsObj);
      }

      return elementsObj;
   }

}

