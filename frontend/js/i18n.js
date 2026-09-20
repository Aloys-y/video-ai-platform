/* UI-only translations. User input, model reports and identifiers are never translated. */
window.I18n = {
  locale: 'zh-CN',
  texts: new WeakMap(),
  attrs: new WeakMap(),
  dictionary: {
    '别只记住输赢。':'Go beyond the result.', '看懂每一次交战。':'Understand every fight.',
    '从录像里找到关键决策，让下一次进圈、拉扯与出手更有依据。':'Find the decisions that mattered. Make your next rotation, reposition and engagement more informed.',
    '交战定位':'Find key fights','战术建议':'Tactical insights','片段回看':'Replay the moment',
    '主导航':'Main navigation',
    'TacEcho · 战术回声 — Apex AI 战术复盘':'TacEcho — Apex AI Tactical Review',
    '战术回声 · 看懂交战，打好下一局':'Read the fight. Own the next drop.',
    '登录':'Sign in','注册':'Create account','邮箱':'Email','密码':'Password','用户名':'Username','或':'or',
    '输入密码':'Enter your password','输入用户名':'Choose a username','至少 6 位':'At least 6 characters',
    '显示密码':'Show password','隐藏密码':'Hide password','GitHub 登录':'Continue with GitHub','退出登录':'Sign out','菜单':'Menu',
    '任务':'Reviews','任务列表':'My reviews','分析任务':'My reviews','我的复盘':'My reviews','任务详情':'Review details','返回列表':'Back to reviews',
    '上传视频':'Upload video','上传':'Upload','回看关键交战，把每一次倒地变成下一次的判断力。':'Revisit key fights. Turn every knockdown into a better decision.',
    '你的下一场，从复盘开始。':'Your next drop starts here.',
    '为 Apex 玩家打造的 AI 战术复盘工作台。':'AI-powered tactical reviews, built for Apex players.',
    '看清交战 · 读懂决策':'REPLAY THE FIGHT. RETHINK THE CALL.',
    '不只回看击杀。':'Beyond the highlight reel.', '更要看懂':'Understand ', '胜负的转折。':'what turned the fight.',
    '自动筛选交战片段，结合英雄、武器与地图知识，':'Find key fights with context from legends, weapons and maps.',
    '逐段分析技能使用、团队配合与失误。':'Review abilities, teamplay and mistakes, one clip at a time.',
    '技能时机':'Ability timing','拉扯与站位':'Positioning','团队决策':'Team decisions',
    '放入录像，开始你的战术复盘':'Drop your footage. Find your next advantage.',
    '拖拽对局视频到这里，或点击选择文件':'Drag your match video here, or browse your files',
    '选择视频':'Choose video','支持 MP4 — 最大 5GB':'MP4 · Up to 5 GB',
    '少翻录像，直达交战':'Less scrubbing. More insight.',
    '从语音线索定位候选交战区间，点击时间标记回看关键现场。':'Find candidate fights from speech cues. Jump back with timestamped observations.',
    '结合 Apex 知识的建议':'Advice grounded in Apex knowledge',
    '结合游戏知识库分析操作与决策，不确定的画面信息明确标注。':'Review your decisions with game knowledge and clearly marked uncertainties.',
    '只分析重点，花费看得见':'Focus the analysis. Track the cost.',
    '筛选后按片段分析，每次模型调用的用量与已知费用均可追溯。':'Analyze selected clips and track usage and known costs for each model call.',
    '暂无分析任务':'No reviews yet','上传一个视频开始 AI 分析':'Upload a match to start your first review.',
    '加载失败':'Unable to load','重试':'Try again','重命名':'Rename','删除':'Delete','强制取消':'Cancel task','重新分析':'Analyze again',
    '任务不存在':'Task not found','任务 ID':'Task ID','创建时间':'Created','开始时间':'Started','完成时间':'Completed','重新分析次数':'Reanalysis count',
    '任务信息':'Task information','时间记录与任务编号':'Dates and task identifier','当前阶段':'Current stage',
    '等待中':'Queued','等待处理':'Queued','分析中':'Analyzing','已完成':'Completed','分析完成':'Analysis complete','部分完成':'Partially completed',
    '失败':'Failed','分析失败':'Analysis failed','已取消':'Cancelled','未知':'Unknown','未知文件':'Unknown file','未知错误':'Unknown error',
    '准备视频与音轨中':'Preparing video and audio','复用已有分析产物中':'Reusing saved artifacts','语音转写中':'Transcribing audio',
    '筛选交战片段中':'Finding combat intervals','裁剪片段与准备分析参考中':'Preparing clips and context','分析片段中':'Analyzing clips',
    '整理分析结果中':'Organizing results','视频分析中':'Analyzing video',
    '阶段完成后自动更新，可稍后回来查看。':'Updates appear as each stage completes. You can return later.',
    '片段分析':'Clip analysis','费用明细':'Cost breakdown','上一段':'Previous','下一段':'Next','暂无可展示的片段。':'No clips to display yet.',
    '仅展示选中片段的分析结果，不代表覆盖整段视频':'Results cover selected clips, not the entire video.',
    '原视频':'Original video','播放片段':'Play clip','收起视频':'Hide video','复用已保存结果':'Saved result reused','复用结果':'Reused result',
    '片段播放 · 时间标记对应原视频':'Clip playback · Timestamps refer to the original video',
    '画面观察':'Observations','问题与建议':'Issues & recommendations','尚不确定':'Uncertainties','参考依据':'Reference',
    '未执行':'Not started','未完成':'Incomplete','等待分析':'Awaiting analysis','本次未完成该片段的分析。':'This clip was not completed in this run.',
    '分析结果将在完成后显示。':'Results will appear when analysis completes.',
    '重新加载片段':'Reload clips','AI 调用费用':'AI usage & costs','刷新':'Refresh','按模型标价估算，不含优惠及存储费用':'Estimated at model list prices; discounts and storage excluded.',
    '本次已知费用':'Known cost · this run','历次累计已知费用':'Known cost · all runs','本次费用':'This run','累计费用':'All runs',
    '本次新增费用':'New charges','复用来源费用':'Reused source cost','本段新增':'New clip charges','已知 Token':'Known tokens','已知用量':'Known usage','调用':'Calls','片段':'Clip','阶段':'Stage',
    '查看费用明细 ↗':'View cost breakdown ↗','查看费用明细 →':'View cost breakdown →','查看历史与复用费用 ↗':'View history & reuse ↗',
    '查看阶段与片段明细':'Stage and clip breakdown','阶段费用 · 用量和调用次数为本次执行':'Stage costs · Usage and calls refer to this run',
    '片段费用 · 复用来源费用仅供追溯，不重复计入本次':'Clip costs · Reused source costs are historical, not charged again',
    '语音转写':'Speech transcription','交战区间识别':'Combat screening','视频分析':'Video analysis','知识查询向量化':'Knowledge embeddings','知识重排':'Knowledge reranking',
    '历史来源：':'Historical source:','已登记调用费用完整':'Recorded call costs are complete','本次未新增模型调用':'No new model calls in this run',
    '仅有部分费用记录':'Cost records are incomplete','尚无完整费用记录':'Complete cost records are not available','费用随处理进度更新':'Costs update as processing continues',
    '含待核对调用，金额尚不完整':'Some calls await reconciliation','费用暂时无法加载':'Costs are temporarily unavailable','费用尚未加载':'Costs have not loaded',
    '费用暂时无法加载，请稍后刷新。':'Costs are unavailable. Please refresh later.',
    '累计金额仅包含已知费用。失败调用也可能计费，待核对记录未计入金额。':'Totals include known costs only. Failed calls may incur charges; unresolved costs are excluded.',
    '记录不完整的历史执行无法确认总费用；空白不代表免费。':'Incomplete historical records cannot confirm total costs. Missing values do not mean free.',
    '复用结果不重复收费。每次实际重试单独记录；语音转写的状态查询不重复计费。“—”表示无已知用量或缺少记录。':'Reused results incur no new charge. Each actual retry is recorded separately; transcription status checks are not charged again. “—” means unknown usage or missing records.',
    '登录成功':'Signed in','注册成功':'Account created','GitHub 登录成功':'Signed in with GitHub','已退出登录':'Signed out',
    '请输入邮箱':'Enter your email','邮箱格式不正确':'Enter a valid email address','请输入密码':'Enter your password','密码至少 6 位':'Password must have at least 6 characters',
    '请输入用户名':'Enter a username','用户名至少 2 个字符':'Username must have at least 2 characters','仅支持字母、数字、下划线、中文':'Use letters, numbers, underscores or Chinese characters',
    '确定要重新分析此任务吗？':'Analyze this task again?','请输入新的任务名称：':'Enter a new task name:',
    '重命名成功':'Renamed','任务已删除':'Task deleted','任务已重新提交':'Task resubmitted',
    '上传中':'Uploading','上传中...':'Uploading…','上传完成':'Upload complete','上传已取消':'Upload cancelled','准备中...':'Preparing…',
    '取消':'Cancel','合并分片...':'Merging upload…','开始分析':'Start analysis','提交中...':'Submitting…','计算文件指纹...':'Checking file…',
    '任务已提交，正在分析...':'Task submitted. Analysis will continue in the background.',
    '秒传成功，文件已存在':'Existing file found. Upload skipped.','文件大小超过 5GB 限制':'File exceeds the 5 GB limit',
    '当前已有上传任务进行中，请等待完成后再选择新文件':'An upload is in progress. Wait before choosing another file.',
    '分析提示词':'Review instructions','确认上传':'Confirm upload','确认分析':'Confirm analysis','文件已就绪':'Your video is ready',
    '处理中...':'Processing…','刚刚':'Just now','错误信息':'Error details',
    '任务已重试，请刷新查看当前结果':'Task restarted. Refresh to see the current results.',
    '任务已重试，请刷新查看本次费用。':'Task restarted. Refresh to see this run’s costs.',
    '片段播放失败，请重新点击时间获取播放地址':'Playback failed. Click the timestamp again to refresh the playback URL.',
    '部分片段未完成，已保存结果见下方':'Some clips are incomplete. Saved results are shown below.',
    '分析未全部完成，请查看各片段结果。':'Analysis is incomplete. Review the individual clip results.',
    '执行中断或租约失效，已保存的片段结果仍可查看，请按需重试。':'Execution stopped or expired. Saved clips remain available; retry if needed.',
    '此任务已取消，已保存的片段结果仍可在下方查看':'Task cancelled. Saved clip results are still available below.',
    '此任务已被取消，没有分析结果':'Task cancelled. No results are available.',
    'RAG 卡片':'Knowledge cards','RAG 导入':'Knowledge import','RAG 检索评估':'Retrieval evaluation',
    '点击或拖拽上传视频文件':'Click or drop a video file','选择分析片段':'Choose a clip','视频任务信息':'Video task information','任务详情视图':'Task detail views',
    '登录注册':'Sign in or create an account','Apex 复盘特色':'Apex review features','产品特色':'Features'
  },
  t(value) {
    if (this.locale !== 'en' || typeof value !== 'string') return value;
    const key = value.trim();
    if (Object.hasOwn(this.dictionary, key)) return value.replace(key, this.dictionary[key]);
    const rules = [
      [/^片段 (\d+)$/, (_, n) => `Clip ${n}`],
      [/^正在查看 (\d+) \/ (\d+)$/, (_, a,b) => `Viewing ${a} of ${b}`],
      [/^已完成 (\d+) \/ (\d+) 个片段$/, (_,a,b) => `${a} of ${b} clips completed`],
      [/^\/ (\d+) 已完成$/, (_,n) => `/ ${n} completed`],
      [/^输入 ([\d,—]+)$/, (_,n) => `Input ${n}`], [/^输出 ([\d,—]+)$/, (_,n) => `Output ${n}`],
      [/^([\d,—]+) 次$/, (_,n) => `${n} calls`], [/^([\d,—]+) 秒$/, (_,n) => `${n} seconds`],
      [/^(\d+) 次调用待核对(.*)$/, (_,n,rest) => `${n} calls pending reconciliation${rest ? ' (some still running)' : ''}`],
      [/^(\d+) 次待核对$/, (_,n) => `${n} unresolved`],
      [/^(\d+) 分钟前$/, (_,n) => `${n} min ago`], [/^(\d+) 小时前$/, (_,n) => `${n} hr ago`], [/^(\d+) 天前$/, (_,n) => `${n} days ago`],
      [/^确定要删除任务「(.*)」吗？此操作不可恢复。$/, (_,name) => `Delete “${name}”? This cannot be undone.`],
      [/^(上传失败|提交失败)：(.*)$/, (_,label,error) => `${label === '上传失败' ? 'Upload failed' : 'Submission failed'}: ${error}`],
      [/^参考依据 · (.*)$/, (_,text) => `Reference · ${text}`],
      [/^定位原视频 (.*)$/, (_,time) => `Seek original video to ${time}`],
      [/^播放片段 (\d+)，原视频 (.*)$/, (_,n,time) => `Play clip ${n} at ${time}`],
      [/^片段 (\d+) 播放器$/, (_,n) => `Clip ${n} player`]
    ];
    for (const [pattern, replacement] of rules) if (pattern.test(key)) return value.replace(key, key.replace(pattern, replacement));
    return value;
  },
  skip(element) {
    return !element || element.closest('script,style,textarea,code,pre,[translate="no"],[contenteditable="true"],.navbar__user,.task-card__name,.task-sidebar__overview-name,.task-sidebar__value,.segment-summary,.segment-event p,.segment-advice strong,.segment-advice p,.segment-uncertainties li,.markdown-body,#upload-filename,#confirm-filename,.upload-progress__filename');
  },
  apply() {
    this.observer?.disconnect();
    try {
      const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
      while(walker.nextNode()) {
        const node = walker.currentNode;
        if(this.skip(node.parentElement)) continue;
        let saved = this.texts.get(node);
        if(!saved || node.nodeValue !== saved.rendered) saved = {source: node.nodeValue};
        const rendered = this.t(saved.source);
        if(node.nodeValue !== rendered) node.nodeValue = rendered;
        this.texts.set(node, {source:saved.source, rendered});
      }
      document.querySelectorAll('[placeholder],[aria-label],[title]').forEach(element => {
        if (element.closest('[translate="no"]')) return;
        const saved = this.attrs.get(element) || {};
        for(const name of ['placeholder','aria-label','title']) {
          if(!element.hasAttribute(name)) continue;
          const value = element.getAttribute(name), old = saved[name];
          const source = old && value === old.rendered ? old.source : value;
          const rendered = this.t(source);
          if(value !== rendered) element.setAttribute(name, rendered);
          saved[name] = {source,rendered};
        }
        this.attrs.set(element,saved);
      });
      document.documentElement.lang=this.locale;
      document.title=this.t('TacEcho · 战术回声 — Apex AI 战术复盘');
      document.querySelectorAll('.locale-select').forEach(select => select.value=this.locale);
    } finally {
      this.observer?.observe(document.body,{subtree:true,childList:true,characterData:true,attributes:true,attributeFilter:['placeholder','aria-label','title']});
    }
  },
  set(locale) {
    if(!['zh-CN','en'].includes(locale)) return;
    this.locale=locale;
    try {localStorage.setItem('tacecho.locale',locale);} catch {}
    this.apply();
  },
  init() {
    try {this.locale=localStorage.getItem('tacecho.locale') || (navigator.language?.startsWith('zh') ? 'zh-CN':'en');} catch {}
    if(!['zh-CN','en'].includes(this.locale)) this.locale='zh-CN';
    this.observer=new MutationObserver(() => this.apply());
    this.apply();
  }
};
document.addEventListener('DOMContentLoaded',()=>I18n.init());
