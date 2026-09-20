const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
function context(api={}) {
  const c=vm.createContext({console,window:{},Api:api,setTimeout,clearTimeout,document:{getElementById:()=>null}});
  for(const name of ['task-cost','task-detail'])vm.runInContext(fs.readFileSync(`frontend/js/${name}.js`,'utf8')+`\nglobalThis.${name==='task-cost'?'TaskCost':'TaskDetail'}=${name==='task-cost'?'TaskCost':'TaskDetail'};`,c);
  c.TaskDetail.render=()=>{};return c;
}
const totals=(covered=false)=>({knownCostCny:'0.0000000000',callCount:0,incompleteCount:0,runningCount:0,inputTokens:null,outputTokens:null,audioSeconds:null,coverageKnown:covered,complete:covered});
const cost=(covered=false)=>({executionNo:1,taskStatus:'SUCCEEDED',current:totals(covered),lifetime:totals(covered),stages:[],segments:[]});
const task={taskId:'task',attemptNo:1,status:'SUCCEEDED',analysisMode:'AUDIO_PREFILTER'};
const segments={executionNo:1,taskStatus:'SUCCEEDED',segments:[],total:0};
test('unknown history is not displayed as free',()=>{
  const html=context().TaskCost.render(cost(),null);assert.match(html,/记录不完整/);assert.doesNotMatch(html,/¥0.00/);
});
test('tiny prices retain decimal precision without Number conversion',()=>{
  const view=context().TaskCost;assert.equal(view.money('0.0000000001'),'¥0.0000000001');
  assert.equal(view.money('123456789012345678.1234567890'),'¥123456789012345678.123456789');
  assert.equal(view.money('0.0000000000'),'¥0.00');
});
test('covered reuse shows zero new charges and pending calls remain explicit',()=>{
  const data=cost(true);data.segments=[{segmentNo:0,reused:true,current:totals(true)}];
  data.lifetime.incompleteCount=1;data.lifetime.runningCount=1;
  const html=context().TaskCost.render(data,null);assert.match(html,/复用结果/);assert.match(html,/¥0.00/);assert.match(html,/待核对/);
});
test('rendered errors are escaped',()=>assert.doesNotMatch(context().TaskCost.render(null,'<img src=x onerror=alert(1)>'),/<img/));
test('cost failure does not hide segment results',async()=>{
  const c=context({get:async url=>url.endsWith('/costs')?Promise.reject(new Error('offline')):url.endsWith('/segments')?segments:task});
  c.TaskDetail.taskId='task';await c.TaskDetail.loadTask();assert.equal(c.TaskDetail.segmentData,segments);assert.equal(c.TaskDetail.costData,null);assert.match(c.TaskDetail.costError,/无法加载/);
});
test('new execution does not display another executions money',async()=>{
  const c=context({get:async url=>url.endsWith('/costs')?{...cost(true),executionNo:2}:url.endsWith('/segments')?segments:task});
  c.TaskDetail.taskId='task';await c.TaskDetail.loadTask();assert.equal(c.TaskDetail.costData,null);assert.match(c.TaskDetail.costError,/任务已重试/);
});
test('late cost response from previous page is ignored',async()=>{
  let release;const late=new Promise(r=>release=r);
  const c=context({get:async url=>url.includes('/old/')?late:url.endsWith('/costs')?cost(true):url.endsWith('/segments')?segments:{...task,taskId:url.endsWith('/old')?'old':'new'}});
  c.TaskDetail.taskId='old';const first=c.TaskDetail.loadTask();await new Promise(r=>setImmediate(r));
  c.TaskDetail.taskId='new';await c.TaskDetail.loadTask();release(cost(false));await first;
  assert.equal(c.TaskDetail.task.taskId,'new');assert.equal(c.TaskDetail.costData.current.coverageKnown,true);
});
