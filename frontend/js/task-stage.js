/** 根据后端阶段显示处理状态，不把阶段权重当作实际百分比。 */
const TaskStage = {
  describe(task) {
    const terminal = { COMPLETED: '分析完成', PARTIALLY_COMPLETED: '部分完成', FAILED: '分析失败', DEAD: '分析失败', CANCELLED: '已取消' };
    if (terminal[task.status]) return terminal[task.status];
    if (task.status !== 'PROCESSING') return '等待处理';
    return ({
      PREPARING_AUDIO: '准备视频与音轨中', TRANSCRIBING: '语音转写中',
      SCREENING: '筛选交战片段中', PREPARING_SEGMENTS: '裁剪片段与准备分析参考中',
      ANALYZING_SEGMENTS: '分析片段中', SUMMARIZING: '整理分析结果中'
    })[task.currentStep] || '视频分析中';
  }
};
