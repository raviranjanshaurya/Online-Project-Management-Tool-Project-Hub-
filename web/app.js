(() => {
  const date = document.getElementById('today');
  if (date) date.textContent = new Intl.DateTimeFormat('en-IN', { day: 'numeric', month: 'short', year: 'numeric' }).format(new Date());
  const toast = document.querySelector('.toast');
  if (toast) { toast.addEventListener('click', () => toast.remove()); setTimeout(() => toast.remove(), 6000); }
  document.querySelectorAll('form').forEach(form => {
    const action = form.querySelector('[name="action"]')?.value;
    if (action === 'deleteProject' || action === 'deleteUser') form.addEventListener('submit', e => { if (!confirm('Are you sure you want to delete this record?')) e.preventDefault(); });
  });
})();

(() => {
  const topbar=document.querySelector('.top-actions');
  if(topbar){
    const search=document.createElement('button');search.className='command-search';search.type='button';search.innerHTML='<span>⌕</span> Search workspace <kbd>Ctrl K</kbd>';
    search.addEventListener('click',()=>{const q=prompt('Search visible tasks and projects');if(!q)return;const cells=[...document.querySelectorAll('td,.task-item,.kanban-card')];const hit=cells.find(x=>x.textContent.toLowerCase().includes(q.toLowerCase()));if(hit){hit.scrollIntoView({behavior:'smooth',block:'center'});hit.style.outline='2px solid #7565ff';setTimeout(()=>hit.style.outline='',2500)}else alert('No matching visible item found')});
    topbar.prepend(search);
    const theme=document.createElement('button');theme.className='theme-button';theme.type='button';theme.title='Toggle dark mode';theme.textContent='◐';theme.addEventListener('click',()=>{document.body.classList.toggle('dark-mode');try{localStorage.setItem('ph-theme',document.body.classList.contains('dark-mode')?'dark':'light')}catch(e){}});topbar.prepend(theme);
    try{if(localStorage.getItem('ph-theme')==='dark')document.body.classList.add('dark-mode')}catch(e){}
    document.addEventListener('keydown',e=>{if((e.ctrlKey||e.metaKey)&&e.key.toLowerCase()==='k'){e.preventDefault();search.click()}});
  }
  const panels=document.querySelectorAll('.panel,.metric');
  if('IntersectionObserver' in window){const obs=new IntersectionObserver(entries=>entries.forEach(e=>{if(e.isIntersecting){e.target.classList.add('in-view');obs.unobserve(e.target)}}),{threshold:.08});panels.forEach(x=>obs.observe(x))}
})();
