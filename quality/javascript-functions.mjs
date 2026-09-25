import fs from 'node:fs';
import path from 'node:path';
import {createRequire} from 'node:module';
const root=path.resolve(import.meta.dirname,'..'),require=createRequire(path.join(root,'backend-v1/package.json'));
const {parse}=require('acorn');
const CRAP_LIMIT=10;
const cov=JSON.parse(fs.readFileSync(path.join(root,'quality/metrics/istanbul/coverage-final.json')));
const rows=[];
const isFunction=n=>['FunctionDeclaration','FunctionExpression','ArrowFunctionExpression'].includes(n.type);
const children=n=>Object.values(n).flatMap(v=>Array.isArray(v)?v:v&&typeof v==='object'?[v]:[]).filter(v=>v?.type);
const within=(p,n)=>p.line>=n.loc.start.line&&p.line<=n.loc.end.line&&(p.line!==n.loc.start.line||p.column>=n.loc.start.column)&&(p.line!==n.loc.end.line||p.column<=n.loc.end.column);
for(const [file,c] of Object.entries(cov)){
 const tree=parse(fs.readFileSync(file,'utf8'),{ecmaVersion:'latest',sourceType:'module',locations:true});
 function visit(n,parent){
  if(isFunction(n)){
   let complexity=1;const nested=[];
   function own(node){
    if(node!==n&&isFunction(node)){nested.push(node);return;}
    if(['IfStatement','ConditionalExpression','ForStatement','ForInStatement','ForOfStatement','WhileStatement','DoWhileStatement','CatchClause','LogicalExpression'].includes(node.type)||node.type==='SwitchCase'&&node.test)complexity++;
    children(node).forEach(own);
   }own(n);
   const owns=location=>within(location,n)&&!nested.some(f=>within(location,f));
   const branchIds=Object.keys(c.branchMap).filter(id=>owns(c.branchMap[id].loc.start));
   let counts=branchIds.flatMap(id=>c.b[id]),basis='branch';
   if(!counts.length){counts=Object.keys(c.statementMap).filter(id=>owns(c.statementMap[id].start)).map(id=>c.s[id]);basis='statement';}
   if(!counts.length){
    const match=Object.keys(c.fnMap).filter(id=>JSON.stringify(c.fnMap[id].loc)===JSON.stringify(n.body.loc));
    if(match.length===1){counts=[c.f[match[0]]];basis='function-execution';}
   }
   const fraction=counts.length?counts.filter(v=>v>0).length/counts.length:null;
   rows.push({component:file.includes('/public/')?'browser':'server',file:path.relative(root,file),name:n.id?.name||parent?.id?.name||parent?.key?.name||`anonymous@${n.loc.start.line}:${n.loc.start.column}`,start:n.loc.start,end:n.loc.end,complexity,coverageBasis:counts.length?basis:'unmapped',covered:counts.filter(v=>v>0).length,total:counts.length,coverageFraction:fraction,crap:fraction===null?null:complexity**2*(1-fraction)**3+complexity});
  }
  children(n).forEach(child=>visit(child,n));
 }visit(tree,null);
}
fs.writeFileSync(path.join(root,'quality/metrics/javascript-functions.json'),JSON.stringify(rows,null,2)+'\n');
for(const component of ['server','browser']){const functions=rows.filter(r=>r.component===component);console.log(component,{functions:functions.length,unmapped:functions.filter(r=>r.crap===null).length,maxComplexity:Math.max(...functions.map(r=>r.complexity)),complexityAboveLimit:functions.filter(r=>r.complexity>CRAP_LIMIT).length,maxCrap:Math.max(...functions.map(r=>r.crap??0)),crapAboveLimit:functions.filter(r=>r.crap>CRAP_LIMIT).length});}
