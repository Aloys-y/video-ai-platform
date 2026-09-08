package com.videoai.worker.screening;

import com.fasterxml.jackson.databind.*;
import com.videoai.common.analysis.*;
import com.videoai.worker.media.*;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 纯计算：模型给证据ID，程序负责时间映射、边界、窗口和预算。 */
@Component
public class CandidatePlanner {
    private final TextAnalysisProperties config;
    private final MediaProperties media;
    private final ObjectMapper json;
    public CandidatePlanner(TextAnalysisProperties config, MediaProperties media, ObjectMapper json) {
        this.config=config; this.media=media; this.json=json;
    }
    public record Window(List<TranscriptUtterance> utterances, String input) {
        public Window { utterances=List.copyOf(utterances); }
    }
    public record Plan(List<CandidateRange> candidates, List<AudioPrefilterPreparationService.Range> ranges, BigDecimal estimatedTaskCny) {
        public Plan { candidates=List.copyOf(candidates); ranges=List.copyOf(ranges); }
    }
    public List<Window> windows(AudioPrefilterPreparationService.TranscriptManifest transcript) throws IOException {
        config.validate();
        if (transcript.durationMs() <= 0) throw new IOException("转写原片时长无效");
        var rows = new ArrayList<>(transcript.utterances()); Set<String> ids=new HashSet<>();
        for (var row:rows) if (!ids.add(row.id()) || row.endMs()>transcript.durationMs()) throw new IOException("转写ID重复或时间超出原片");
        rows.sort(Comparator.comparingLong(TranscriptUtterance::startMs).thenComparingLong(TranscriptUtterance::endMs));
        List<Window> windows=new ArrayList<>();
        for (int start=0; start<rows.size();) {
            int end=start; String input=null;
            while (end<rows.size() && end-start<config.getBatchSize()) {
                String candidate=json.writeValueAsString(rows.subList(start,end+1));
                if (inputBytes(TextPrompts.SCREEN,candidate)>config.getMaxRequestBytes()) break;
                input=candidate; end++;
            }
            if (end==start) throw new IOException("单个转写句段超过文本输入预算，需要先处理长句");
            windows.add(new Window(rows.subList(start,end),input));
            if (windows.size()>config.getMaxBatches()) throw new IOException("粗筛批次数超过预算");
            if (end==rows.size()) break;
            start=Math.max(start+1,end-config.getOverlap());
        }
        return List.copyOf(windows);
    }
    public static int inputBytes(String system,String input) {
        return Math.addExact(Math.addExact(system.getBytes(StandardCharsets.UTF_8).length,input.getBytes(StandardCharsets.UTF_8).length),1024);
    }
    public BigDecimal estimate(long durationMs,List<Window> windows,long selectedMs) throws IOException {
        config.validate();
        // UTF-8字节数作保守输入token预算，并预留一次最大汇总调用；不是账单。
        long input=config.getMaxRequestBytes();
        for (Window w:windows) input+=inputBytes(TextPrompts.SCREEN,w.input());
        BigDecimal text=BigDecimal.valueOf(input).multiply(config.getInputCnyPerMillion())
                .add(BigDecimal.valueOf((long)(windows.size()+1)*config.getMaxOutputTokens()).multiply(config.getOutputCnyPerMillion()))
                .movePointLeft(6);
        BigDecimal total=text.add(BigDecimal.valueOf(durationMs,3).multiply(config.getAsrCnyPerSecond()))
                .add(BigDecimal.valueOf(selectedMs).multiply(config.getVideoReserveCnyPerMinute()).divide(BigDecimal.valueOf(60000),8,java.math.RoundingMode.UP));
        if (total.compareTo(config.getMaxEstimatedTaskCny())>0) throw new IOException("预计整局费用超过预算，不静默删除候选");
        return total;
    }
    public List<CandidateRange> parse(String content,Window window) throws IOException {
        JsonNode raw;
        try { raw=json.readTree(stripFence(content)); } catch (IOException e) { throw new InvalidTextResultException("候选JSON无法解析"); }
        if(raw==null) throw new InvalidTextResultException("候选响应为空");
        JsonNode array=raw.isArray()?raw:raw.path("candidates");
        if (!array.isArray() || array.size()>config.getMaxCandidates()) throw new InvalidTextResultException("候选数组缺失或超出数量限制");
        Map<String,TranscriptUtterance> rows=new HashMap<>();window.utterances().forEach(r->rows.put(r.id(),r));
        List<CandidateRange> result=new ArrayList<>();
        for (JsonNode item:array) {
            if(item.has("startMs") || item.has("endMs") || item.has("start_ms") || item.has("end_ms"))
                throw new InvalidTextResultException("模型不应生成时间戳，必须引用句段ID");
            if (!item.path("utterance_ids").isArray() || item.path("utterance_ids").isEmpty()
                    || !item.path("reason").isTextual() || item.path("reason").asText().isBlank()) throw new InvalidTextResultException("候选缺少来源或理由");
            CandidateRange.Type type;
            try { type=CandidateRange.Type.valueOf(item.path("type").asText().toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException e) { throw new InvalidTextResultException("未知候选类型"); }
            List<TranscriptUtterance> evidence=new ArrayList<>();Set<String> unique=new HashSet<>();
            for (JsonNode id:item.path("utterance_ids")) {
                if (!id.isTextual() || !rows.containsKey(id.asText())) throw new InvalidTextResultException("候选引用不存在或跨窗口的句段ID");
                if (unique.add(id.asText())) evidence.add(rows.get(id.asText()));
            }
            evidence.sort(Comparator.comparingLong(TranscriptUtterance::startMs));
            // 即使模型合并了相距很远的证据，也不把中间长段闲聊当成交战。
            List<String> group=new ArrayList<>();long start=0,end=0;
            for (var row:evidence) {
                if (!group.isEmpty() && row.startMs()>end+config.getMergeGapMs()) {
                    result.add(new CandidateRange(start,end,group,type,item.path("reason").asText()));group=new ArrayList<>();
                }
                if (group.isEmpty()) start=row.startMs();
                group.add(row.id());end=Math.max(end,row.endMs());
            }
            result.add(new CandidateRange(start,end,group,type,item.path("reason").asText()));
        }
        return result;
    }
    public Plan plan(List<CandidateRange> candidates,long duration,List<Window> windows) throws IOException {
        if (candidates.size()>config.getMaxCandidates()) throw new IOException("候选总数超过预算");
        List<CandidateRange> unique=new ArrayList<>(new LinkedHashSet<>(candidates));
        List<AudioPrefilterPreparationService.Range> raw=new ArrayList<>();
        for (var c:unique) {
            if (c.endMs()>duration) throw new IOException("候选超出原片");
            raw.add(new AudioPrefilterPreparationService.Range(c.startMs(),c.endMs()));
        }
        List<AudioPrefilterPreparationService.Range> expanded=new ArrayList<>();
        for (var r:merge(raw,config.getMergeGapMs())) expanded.add(new AudioPrefilterPreparationService.Range(
                Math.max(0,r.startMs()-config.getBeforeMs()),Math.min(duration,r.endMs()+config.getAfterMs())));
        List<AudioPrefilterPreparationService.Range> ranges=new ArrayList<>();long total=0;
        if (media.getMaxSegmentMs()<=0 || media.getMaxSegments()<=0) throw new IOException("单片段预算无效");
        // 先合并/扩展，再按时间拆分并保留前N段；完整候选仍写入清单，便于审计覆盖范围。
        selected:
        for (var r:merge(expanded,0)) {
            for (long s=r.startMs();s<r.endMs();) {
                if (ranges.size()>=media.getMaxSegments()) break selected;
                long e=Math.min(r.endMs(),s+media.getMaxSegmentMs());
                ranges.add(new AudioPrefilterPreparationService.Range(s,e));total+=e-s;s=e;
            }
        }
        if (total>media.getMaxSelectedMs()) throw new IOException("筛中时长超过预算");
        return new Plan(unique,ranges,estimate(duration,windows,total));
    }
    private List<AudioPrefilterPreparationService.Range> merge(List<AudioPrefilterPreparationService.Range> input,long gap) {
        input.sort(Comparator.comparingLong(AudioPrefilterPreparationService.Range::startMs));
        List<AudioPrefilterPreparationService.Range> out=new ArrayList<>();
        for(var r:input) {
            if(!out.isEmpty() && r.startMs()<=out.get(out.size()-1).endMs()+gap) {
                var last=out.remove(out.size()-1);out.add(new AudioPrefilterPreparationService.Range(last.startMs(),Math.max(last.endMs(),r.endMs())));
            } else out.add(r);
        }
        return out;
    }
    public static String stripFence(String content) {
        String text=content.strip();
        if(text.startsWith("```")) text=text.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        return text;
    }
    public static final class InvalidTextResultException extends IOException {
        public InvalidTextResultException(String message) { super(message); }
    }
}
