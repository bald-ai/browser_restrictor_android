import java.nio.file.*;
import java.util.*;
import org.jetbrains.kotlin.cli.jvm.compiler.*;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.psi.*;
import org.jetbrains.kotlin.psi.*;
import org.jetbrains.kotlin.lexer.KtTokens;
import org.jetbrains.org.objectweb.asm.ClassReader;
import java.security.MessageDigest;

/** Kotlin compiler PSI supplies the same function boundaries as compilation. */
public class KotlinFunctions {
 static boolean function(PsiElement e) {
  return e instanceof KtNamedFunction || e instanceof KtFunctionLiteral || e instanceof KtSecondaryConstructor || e instanceof KtPrimaryConstructor || e instanceof KtPropertyAccessor || e instanceof KtAnonymousInitializer;
 }
 static boolean abstractDeclaration(PsiElement e) {
  if (!(e instanceof KtNamedFunction f) || f.hasBody() || f.hasModifier(KtTokens.EXTERNAL_KEYWORD)) return false;
  return f.hasModifier(KtTokens.ABSTRACT_KEYWORD)
      || (f.getParent() instanceof KtClassBody body && body.getParent() instanceof KtClass owner && owner.isInterface());
 }
 static int complexity(PsiElement e, boolean root) {
  if(!root && function(e))return 0;
  int n=0;
  if(e instanceof KtIfExpression || e instanceof KtLoopExpression || e instanceof KtCatchClause) n++;
  if(e instanceof KtWhenEntry entry && !entry.isElse()) n+=entry.getConditions().length;
  if(e instanceof KtBinaryExpression b && Set.of("&&","||","?:").contains(b.getOperationReference().getText()))n++;
  for(PsiElement c:e.getChildren())n+=complexity(c,false);
  return n;
 }
 static void visit(PsiElement e, String path, String source) {
  if(e instanceof PsiErrorElement error)throw new IllegalStateException(path+": "+error.getErrorDescription());
  if(function(e)) {
   int start=e.getTextRange().getStartOffset(),end=e.getTextRange().getEndOffset();
   String name=e instanceof KtNamedFunction f?f.getName():e.getClass().getSimpleName();
   if(e instanceof KtPropertyAccessor accessor)name=(accessor.isGetter()?"get:":"set:")+accessor.getProperty().getName();
   long startLine=1+source.substring(0,start).chars().filter(c->c=='\n').count();
   long endLine=1+source.substring(0,Math.max(start,end-1)).chars().filter(c->c=='\n').count();
   String kind=abstractDeclaration(e)?"abstract-declaration":"executable";
   System.out.println(path+"\t"+name+"\t"+start+"\t"+end+"\t"+startLine+"\t"+endLine+"\t"+(1+complexity(e,true))+"\t"+kind);
  }
  for(PsiElement c:e.getChildren())visit(c,path,source);
 }
 public static void main(String[] args)throws Exception {
  if(args[0].equals("--classes")) {
   try(var files=Files.walk(Path.of(args[1]))) {
    for(Path path:files.filter(p->p.toString().endsWith(".class")).sorted().toList()) {
     byte[] bytes=Files.readAllBytes(path);
     var reader=new ClassReader(bytes);
     System.out.println(reader.getClassName()+"\t"+reader.getSuperName()+"\t"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }
   }
   return;
  }
  var disposable=Disposer.newDisposable();
  try {
   var env=KotlinCoreEnvironment.createForProduction(disposable,new CompilerConfiguration(),EnvironmentConfigFiles.JVM_CONFIG_FILES);
   var factory=new KtPsiFactory(env.getProject(),false);
   try(var files=Files.walk(Path.of(args[0]))) {
    for(Path p:files.filter(p->p.toString().endsWith(".kt")).sorted().toList()) {
     String s=Files.readString(p);visit(factory.createFile(p.getFileName().toString(),s),p.toString(),s);
    }
   }
  }finally{Disposer.dispose(disposable);}
 }
}
