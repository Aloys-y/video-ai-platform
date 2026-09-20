/** 只展示 API 已汇总的费用，不在浏览器重新计算价格。 */
const TaskCost = {
  escape(value) { return String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])); },
  number(value) { return value == null ? '—' : this.escape(String(value).replace(/\B(?=(\d{3})+(?!\d))/g, ',')); },
  money(value) {
    if (value == null || !/^\d+(\.\d+)?$/.test(String(value))) return '—';
    const clean = String(value).replace(/(\.\d*?)0+$/, '$1').replace(/\.$/, '');
    return '¥' + (clean.includes('.') ? clean : clean + '.00');
  },
  amount(totals) {
    return !totals || (!totals.coverageKnown && totals.callCount === 0) ? '—' : this.money(totals.knownCostCny);
  },
  note(totals, active) {
    if (!totals.coverageKnown) return totals.callCount ? '仅有部分费用记录' : '尚无完整费用记录';
    if (totals.incompleteCount) return `${this.number(totals.incompleteCount)} 次调用待核对${totals.runningCount ? `（${this.number(totals.runningCount)} 次尚未收尾）` : ''}`;
    if (active) return '费用随处理进度更新';
    return totals.callCount ? '已登记调用费用完整' : '本次未新增模型调用';
  },
  render(data, error, expanded = false) {
    const head = '<div class="task-cost__heading"><div><h3>AI 调用费用</h3><p>按模型标价估算，不含优惠及存储费用</p></div><button class="btn btn--ghost btn--small" onclick="TaskDetail.retryLoad()">刷新</button></div>';
    if (error || !data) return `<section class="card task-cost" aria-label="AI 调用费用">${head}<p class="task-cost__muted" role="status">${this.escape(error || '费用尚未加载')}</p></section>`;
    const active = !['SUCCEEDED','PARTIAL','FAILED','CANCELLED'].includes(data.taskStatus);
    const names = {ASR:'语音转写',TEXT_SCREEN:'交战区间识别',VIDEO_ANALYSIS:'视频分析',RAG_EMBEDDING:'知识查询向量化',RAG_RERANK:'知识重排'};
    const usage = (value, stage) => stage === 'ASR' ? `${this.number(value.audioSeconds)} 秒`
      : `输入 ${this.number(value.inputTokens)}<br>输出 ${this.number(value.outputTokens)}`;
    const calls = value => `${this.number(value.callCount)} 次${value.incompleteCount ? `<br><span class="task-cost__pending">${this.number(value.incompleteCount)} 次待核对</span>` : ''}`;
    const card = (title, total) => `<div class="task-cost__metric"><span>${title}</span><strong>${this.amount(total)}</strong><small>${this.note(total,active)}</small></div>`;
    return `<section class="card task-cost" aria-label="AI 调用费用">${head}
      <div class="task-cost__metrics">${card('本次已知费用',data.current)}${card('历次累计已知费用',data.lifetime)}</div>
      ${!data.lifetime.coverageKnown ? '<p class="task-cost__muted">记录不完整的历史执行无法确认总费用；空白不代表免费。</p>' : ''}
      ${data.lifetime.incompleteCount ? '<p class="task-cost__muted">累计金额仅包含已知费用。失败调用也可能计费，待核对记录未计入金额。</p>' : ''}
      <details class="task-cost__details" ${expanded ? 'open' : ''} ontoggle="TaskDetail.costExpanded=this.open">
        <summary>查看阶段与片段明细</summary>
        <div class="task-cost__table-wrap"><table><caption>阶段费用 · 用量和调用次数为本次执行</caption><thead><tr><th scope="col">阶段</th><th scope="col">本次费用</th><th scope="col">累计费用</th><th scope="col">已知用量</th><th scope="col">调用</th></tr></thead><tbody>
          ${data.stages.map(row => `<tr><th scope="row">${this.escape(names[row.stage] || row.stage)}</th><td>${this.amount(row.current)}</td><td>${this.amount(row.lifetime)}</td><td>${usage(row.current,row.stage)}</td><td>${calls(row.current)}</td></tr>`).join('')}
        </tbody></table></div>
        ${data.segments.length ? `<div class="task-cost__table-wrap"><table><caption>片段费用 · 复用来源费用仅供追溯，不重复计入本次</caption><thead><tr><th scope="col">片段</th><th scope="col">本次新增费用</th><th scope="col">复用来源费用</th><th scope="col">已知 Token</th><th scope="col">调用</th></tr></thead><tbody>
          ${data.segments.map(row => `<tr><th scope="row">片段 ${this.number(Number(row.segmentNo)+1)}${row.reused ? '<br><span class="task-cost__muted">复用结果</span>' : ''}</th><td>${this.amount(row.current)}</td><td>${row.reused ? this.amount(row.reusedSource) : '—'}</td><td>${row.reused && row.reusedSource ? '历史来源：<br>' + usage(row.reusedSource,'VIDEO_ANALYSIS') : usage(row.current,'VIDEO_ANALYSIS')}</td><td>${row.reused && row.reusedSource ? '历史来源：' + calls(row.reusedSource) : calls(row.current)}</td></tr>`).join('')}
        </tbody></table></div>` : ''}
        <p class="task-cost__muted">复用结果不重复收费。每次实际重试单独记录；语音转写的状态查询不重复计费。“—”表示无已知用量或缺少记录。</p>
      </details></section>`;
  }
};
