/* Domain management is kept outside the disabled AI fieldset so deletion always works. */
(function(){
  'use strict';
  const field=id=>document.getElementById(id);
  let domains=[],busy=false,loaded=false;
  function controls(){
    for(const id of ['memory-refresh','memory-clear','memory-confirm-clear','memory-search'])field(id).disabled=busy;
    field('memory-clear').disabled=busy||!domains.length;
  }
  function render(){
    const query=field('memory-search').value.trim().toLowerCase();
    field('memory-domains').replaceChildren();
    const visible=domains.filter(d=>d.host.includes(query));
    field('memory-summary').textContent=domains.length?domains.length+' dominios · '+domains.reduce((n,d)=>n+d.rules,0)+' huellas · '+domains.reduce((n,d)=>n+d.hits,0)+' reutilizadas.':loaded?'Sin anuncios recordados todavía.':'Consultando memoria…';
    for(const domain of visible){
      const card=document.createElement('article');card.className='memory-domain';
      const title=document.createElement('h3');title.textContent=domain.host;
      const detail=document.createElement('p');detail.className='hint';detail.textContent=domain.rules+' huellas · '+domain.hits+' reutilizaciones · '+new Date(domain.lastUsed).toLocaleDateString('es');
      const actions=document.createElement('div');actions.className='memory-actions';
      for(const [label,type] of [['Olvidar','AI_MEMORY_FORGET'],['Excluir de IA','AI_MEMORY_EXCLUDE']]){
        const button=document.createElement('button');button.type='button';button.className='secondary';button.textContent=label;button.disabled=busy;
        button.setAttribute('aria-label',label+' '+domain.host);
        button.addEventListener('click',()=>request(type,domain.host));actions.appendChild(button);
      }
      card.append(title,detail,actions);field('memory-domains').appendChild(card);
    }
    if(domains.length && !visible.length){const empty=document.createElement('p');empty.textContent='No hay dominios que coincidan.';field('memory-domains').appendChild(empty);}
    controls();
  }
  async function request(type,host){
    if(busy)return;busy=true;render();
    try{
      const result=await browser.runtime.sendMessage({type,host});
      if(!result || !result.ok)throw Error(result && result.error || 'No se pudo consultar la memoria.');
      domains=result.domains||[];loaded=true;
      field('memory-status').textContent=type==='AI_MEMORY_LIST'?'':type==='AI_MEMORY_EXCLUDE'?'Dominio excluido de IA.':'Memoria borrada.';
      field('memory-confirm').hidden=true;
    }catch(error){field('memory-status').textContent=error.message || 'No se pudo completar la operación.';}
    finally{busy=false;render();}
  }
  field('memory-search').addEventListener('input',render);
  field('memory-refresh').addEventListener('click',()=>request('AI_MEMORY_LIST'));
  field('memory-clear').addEventListener('click',()=>{field('memory-confirm').hidden=false;field('memory-confirm-clear').focus();});
  field('memory-cancel').addEventListener('click',()=>{field('memory-confirm').hidden=true;field('memory-clear').focus();});
  field('memory-confirm-clear').addEventListener('click',()=>request('AI_MEMORY_CLEAR'));
  request('AI_MEMORY_LIST');
})();
