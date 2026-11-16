// Gera os PNGs do ícone: node scripts/gerar-icone.js android/app/src/main/res
const { chromium } = require('playwright'); const fs=require('fs');
(async()=>{
  const b=await chromium.launch(); const p=await b.newPage();
  const fg=fs.readFileSync('assets/icone/frente.svg','utf8'), bg=fs.readFileSync('assets/icone/fundo.svg','utf8');
  const out=process.argv[2];
  const dens={mdpi:1,hdpi:1.5,xhdpi:2,xxhdpi:3,xxxhdpi:4};
  async function shot(html,size,file,transparent){ await p.setViewportSize({width:size,height:size}); await p.setContent(`<html><body style="margin:0;background:transparent">${html}</body></html>`); await p.screenshot({path:file,omitBackground:!!transparent,clip:{x:0,y:0,width:size,height:size}}); }
  for(const [d,k] of Object.entries(dens)){
    const dir=`${out}/mipmap-${d}`;
    const A=Math.round(108*k), L=Math.round(48*k);
    const svg=(s,sz)=>s.replace('<svg ',`<svg width="${sz}" height="${sz}" style="display:block" `);
    await shot(svg(fg,A),A,`${dir}/ic_launcher_foreground.png`,true);
    await shot(svg(bg,A),A,`${dir}/ic_launcher_background.png`,false);
    // legado: fundo + frente recortados (zona visível 72/108), quadrado arredondado e redondo
    const legacy=(radius)=>`<div style="width:${L}px;height:${L}px;border-radius:${radius};overflow:hidden;position:relative"><div style="position:absolute;left:${-L*18/72}px;top:${-L*18/72}px">${svg(bg,L*108/72)}</div><div style="position:absolute;left:${-L*18/72}px;top:${-L*18/72}px">${svg(fg,L*108/72)}</div></div>`;
    await shot(legacy('22%'),L,`${dir}/ic_launcher.png`,true);
    await shot(legacy('50%'),L,`${dir}/ic_launcher_round.png`,true);
  }
  await shot(`<div style="display:flex;gap:16px;padding:16px;background:#e9eef5">`+['22%','50%'].map(r=>`<div style="width:192px;height:192px;border-radius:${r};overflow:hidden;position:relative"><div style="position:absolute;left:-48px;top:-48px">${svg2(bg)}</div><div style="position:absolute;left:-48px;top:-48px">${svg2(fg)}</div></div>`).join('')+`</div>`,440,'preview.png',false);
  function svg2(s){return s.replace('<svg ','<svg width="288" height="288" style="display:block" ');}
  await b.close();
})();
