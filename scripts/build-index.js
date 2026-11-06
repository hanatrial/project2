// Membuat index.html: daftar repo hanatrial -> klik repo -> daftar halaman HTML-nya.
// Mode API (default, dipakai GitHub Actions): node scripts/build-index.js
// Mode lokal (clone ada di folder): node scripts/build-index.js --local /home/user
const fs = require('fs'), path = require('path'), cp = require('child_process');
const OWNER = 'hanatrial', SELF = 'project2';
// Sumber GitHub Pages yang bukan "branch utama, folder root"
const SRC = { workbook:{branch:'master',dir:'docs'}, var:{branch:'gh-pages',dir:''} };
const SKIP = /(^|\/)(vendor|node_modules|\.github|\.claude)\//;
const TOKEN = process.env.GITHUB_TOKEN;
const H = { 'User-Agent':'project2-index', Accept:'application/vnd.github+json', ...(TOKEN?{Authorization:'Bearer '+TOKEN}:{}) };
const api = async u => { const r = await fetch('https://api.github.com'+u,{headers:H}); if(!r.ok) throw new Error(u+' '+r.status); return r.json(); };

function pick(files, dir){
  const pre = dir ? dir.replace(/\/$/,'')+'/' : '';
  return files.filter(f=>/\.html?$/i.test(f) && f.startsWith(pre) && !SKIP.test(f)).map(f=>f.slice(pre.length)).sort();
}
const DAYS=30, SINCE=new Date(Date.now()-DAYS*864e5).toISOString();
const fullp=(dir,f)=>dir?dir.replace(/\/$/,'')+'/'+f:f;
async function stat(repo,branch,p){ // [tanggal commit terakhir, jumlah commit 30 hari terakhir]
  const q=`/repos/${OWNER}/${repo}/commits?sha=${encodeURIComponent(branch)}&per_page=100`+(p?`&path=${encodeURIComponent(p)}`:'');
  const c=await api(q); const ds=c.map(x=>x.commit.committer.date);
  return {mod:ds[0]||'',n:ds.filter(d=>d>=SINCE).length};
}
async function viaApi(){
  const repos = (await api(`/users/${OWNER}/repos?per_page=100`)).filter(r=>!r.fork&&!r.archived&&r.name!==SELF);
  const out = [];
  for(const r of repos){
    const s = SRC[r.name] || {branch:r.default_branch,dir:''};
    try{
      const t = await api(`/repos/${OWNER}/${r.name}/git/trees/${s.branch}?recursive=1`);
      const files=pick(t.tree.filter(x=>x.type==='blob').map(x=>x.path),s.dir);
      const rs=await stat(r.name,s.branch,'').catch(()=>({mod:'',n:0}));
      const fst={};
      for(const f of files) fst[f]=await stat(r.name,s.branch,fullp(s.dir,f)).catch(()=>({mod:'',n:0}));
      out.push({name:r.name,files,pushed:r.pushed_at||rs.mod,n:rs.n,fst});
    }catch(e){ console.error('lewati',r.name,e.message); }
  }
  return out;
}
function viaLocal(base){
  const map = {'insentif-hanatrial':'insentif'}, out = [];
  for(const d of fs.readdirSync(base)){
    const dir = path.join(base,d); if(!fs.existsSync(path.join(dir,'.git'))||d==='insentif'||d===SELF) continue;
    const name = map[d]||d, s = SRC[name]||{branch:null,dir:''};
    let ref = s.branch ? 'origin/'+s.branch : 'HEAD';
    try{ cp.execSync(`git -C ${dir} fetch -q origin ${s.branch||''}`,{stdio:'ignore'}); }catch(e){}
    try{
      const ls = cp.execSync(`git -C ${dir} ls-tree -r --name-only ${ref}`,{encoding:'utf8'}).split('\n').filter(Boolean);
      let pushed='';try{pushed=cp.execSync(`git -C ${dir} log -1 --format=%cI ${ref}`,{encoding:'utf8'}).trim()}catch(e){}
      const files=pick(ls,s.dir), g=(p)=>{try{const d=cp.execSync(`git -C ${dir} log ${ref} --format=%cI ${p?'-- '+JSON.stringify(p):''} | head -100`,{encoding:'utf8',shell:'/bin/bash'}).split('\n').filter(Boolean);return {mod:d[0]||'',n:d.filter(x=>x>=SINCE).length}}catch(e){return {mod:'',n:0}}};
      const fst={};for(const f of files)fst[f]=g(fullp(s.dir,f));
      out.push({name,files,pushed,n:g('').n,fst});
    }catch(e){ console.error('lewati',name,e.message); }
  }
  return out;
}
const url = (repo,f)=>`https://${OWNER}.github.io/${repo}/`+f.replace(/(^|\/)index\.html?$/i,'$1');
(async()=>{
  const i = process.argv.indexOf('--local');
  const repos = (i>0 ? viaLocal(process.argv[i+1]) : await viaApi()).filter(r=>r.files.length)
    .sort((a,b)=>a.name.localeCompare(b.name,'id',{sensitivity:'base'}));
  repos.forEach(r=>r.items=r.files.map(f=>({f,u:url(r.name,f),mod:(r.fst[f]||{}).mod||'',n:(r.fst[f]||{}).n||0})));
  const data = {updated:new Date().toISOString(),repos:repos.map(r=>({name:r.name,home:url(r.name,''),pushed:r.pushed,n:r.n||0,items:r.items}))};
  const tpl = fs.readFileSync(path.join(__dirname,'template.html'),'utf8');
  const out = path.join(__dirname,'..','index.html');
  try{ const old = JSON.parse(fs.readFileSync(out,'utf8').match(/const D=(\{.*?\}),v=/s)[1]);
    if(JSON.stringify(old.repos)===JSON.stringify(data.repos)){ console.log('tidak ada perubahan'); return; } }catch(e){}
  fs.writeFileSync(out, tpl.replace('/*DATA*/null', JSON.stringify(data)));
  console.log('repo:',repos.length,'halaman:',repos.reduce((n,r)=>n+r.files.length,0));
})();
