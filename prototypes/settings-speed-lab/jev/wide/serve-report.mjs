import http from 'node:http';
import fs from 'node:fs/promises';
import path from 'node:path';
const root=path.resolve(process.argv[2]||path.join(import.meta.dirname,'../../results/jev-wide/report'));
const allowed=new Set(['dashboard.html','REPORT.md','trials.csv','trials.json','summary.json','dashboard-preview.png']);
http.createServer(async(req,res)=>{const name=new URL(req.url,'http://localhost').pathname.slice(1)||'dashboard.html';if(!allowed.has(name)){res.writeHead(404);res.end('Not found');return;}try{const data=await fs.readFile(path.join(root,name));res.setHeader('Content-Type',({'.html':'text/html; charset=utf-8','.md':'text/plain; charset=utf-8','.json':'application/json','.csv':'text/csv; charset=utf-8','.png':'image/png'})[path.extname(name)]);res.end(data);}catch{res.writeHead(404);res.end('Report not generated yet');}}).listen(4343,'127.0.0.1',()=>console.log('Broad Jev experiment: http://localhost:4343'));
