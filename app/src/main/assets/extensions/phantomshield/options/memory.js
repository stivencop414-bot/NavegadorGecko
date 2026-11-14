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

  /* Dominios publicitarios aprendidos con la IA (bloqueados u observando). */
  let learned={domains:[],allowed:[]},learnedBusy=false,learnedLoaded=false;
  function learnedControls(){
    for(const id of ['learned-refresh','learned-search'])field(id).disabled=learnedBusy;
    field('learned-clear').disabled=learnedBusy||!learned.domains.length;
  }
  function domainCard(title,detail,buttons){
    const card=document.createElement('article');card.className='memory-domain';
    const heading=document.createElement('h3');heading.textContent=title;
    const text=document.createElement('p');text.className='hint';text.textContent=detail;
    const actions=document.createElement('div');actions.className='memory-actions';
    for(const [label,type,domain] of buttons){
      const button=document.createElement('button');button.type='button';button.className='secondary';button.textContent=label;button.disabled=learnedBusy;
      button.setAttribute('aria-label',label+' '+domain);
      button.addEventListener('click',()=>learnedRequest(type,domain));actions.appendChild(button);
    }
    card.append(heading,text,actions);return card;
  }
  function renderLearned(){
    const query=field('learned-search').value.trim().toLowerCase();
    const list=field('learned-domains');list.replaceChildren();
    const blocked=learned.domains.filter(d=>d.state==='blocked').length;
    field('learned-summary').textContent=learned.domains.length
      ?blocked+' bloqueados · '+(learned.domains.length-blocked)+' en observación.'
      :learnedLoaded?'Aún no hay dominios aprendidos. Se guardan cuando la IA oculta anuncios.':'Consultando dominios…';
    for(const d of learned.domains.filter(d=>d.domain.includes(query))){
      const state=d.state==='blocked'?'Bloqueado':'Observando';
      const where=d.sites===1?'1 sitio':d.sites+' sitios';
      list.appendChild(domainCard(d.domain,state+' · visto en '+where+' · '+d.hits+(d.hits===1?' vez':' veces')+' · '+new Date(d.last).toLocaleDateString('es'),
        [['Olvidar','AI_DOMAINS_FORGET',d.domain],['Nunca bloquear','AI_DOMAINS_ALLOW',d.domain]]));
    }
    const allowed=field('learned-allowed');allowed.replaceChildren();
    field('learned-allowed-box').hidden=!learned.allowed.length;
    for(const domain of learned.allowed)allowed.appendChild(domainCard(domain,'No se aprenderá ni se bloqueará.',[['Quitar','AI_DOMAINS_UNALLOW',domain]]));
    learnedControls();
  }
  async function learnedRequest(type,domain){
    if(learnedBusy)return;learnedBusy=true;renderLearned();
    try{
      const result=await browser.runtime.sendMessage({type,domain});
      if(!result||!result.ok)throw Error(result&&result.error||'No se pudieron leer los dominios aprendidos.');
      learned={domains:result.domains||[],allowed:result.allowed||[]};learnedLoaded=true;
      field('learned-status').textContent={AI_DOMAINS_FORGET:'Dominio olvidado.',AI_DOMAINS_ALLOW:'No se volverá a bloquear.',
        AI_DOMAINS_UNALLOW:'Se podrá volver a aprender.',AI_DOMAINS_CLEAR:'Dominios aprendidos borrados.'}[type]||'';
    }catch(error){field('learned-status').textContent=error.message||'No se pudo completar la operación.';}
    finally{learnedBusy=false;renderLearned();}
  }
  field('learned-search').addEventListener('input',renderLearned);
  field('learned-refresh').addEventListener('click',()=>learnedRequest('AI_DOMAINS_LIST'));
  // Confirmación en el propio botón (los diálogos del navegador no siempre se muestran en la app).
  let clearArmed=null;
  field('learned-clear').addEventListener('click',()=>{
    const button=field('learned-clear');
    if(clearArmed){clearTimeout(clearArmed);clearArmed=null;button.textContent='Olvidar todos los dominios aprendidos';learnedRequest('AI_DOMAINS_CLEAR');return;}
    button.textContent='Toca otra vez para olvidarlos todos';
    clearArmed=setTimeout(()=>{clearArmed=null;button.textContent='Olvidar todos los dominios aprendidos';},4000);
  });
  learnedRequest('AI_DOMAINS_LIST');
})();
