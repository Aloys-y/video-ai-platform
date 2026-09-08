"""P0 工具的离线契约测试；模拟数据不能作为粗筛效果验收。"""
import unittest
from asr_prefilter_p0 import normalize_transcript, result_urls, select_ranges, evaluation, redact, parse_candidates, request_json
from unittest.mock import patch, MagicMock


class PrefilterTests(unittest.TestCase):
    def test_parse_saved_array_without_repeating_cloud_call(self):
        response = {"choices": [{"finish_reason": "stop", "message": {"content": "[]"}}]}
        self.assertEqual(parse_candidates(response), [])
        response["choices"][0]["finish_reason"] = "length"
        with self.assertRaises(ValueError):
            parse_candidates(response)

    def test_signed_download_has_no_content_type_or_authorization(self):
        opener = MagicMock()
        opener.open.return_value.__enter__.return_value.read.return_value = b'{}'
        with patch('urllib.request.build_opener', return_value=opener):
            request_json('https://example.com/result.json')
        req = opener.open.call_args.args[0]
        self.assertFalse(req.has_header('Content-type'))
        self.assertFalse(req.has_header('Authorization'))

    def test_timestamp_normalization(self):
        raw = {"transcripts": [{"channel_id": 0, "sentences": [
            {"begin_time": 2000, "end_time": 3500, "text": "打他"},
            {"begin_time": 0, "end_time": 1000, "text": "前面有人"}]}]}
        rows = normalize_transcript(raw, 500)["utterances"]
        self.assertEqual(rows[0]["id"], "u00001")
        self.assertEqual(rows[0]["start_ms"], 500)
        self.assertEqual(rows[1]["end_ms"], 4000)

    def test_missing_timestamps_rejected(self):
        with self.assertRaises(ValueError):
            normalize_transcript({"transcripts": [{"text": "没有时间戳"}]})

    def test_failed_subtask_not_success(self):
        with self.assertRaises(ValueError):
            result_urls({"output": {"task_status": "SUCCEEDED", "results": [
                {"subtask_status": "FAILED"}]}})

    def test_clip_expansion_clamps_and_merges(self):
        rows = [{"id": "u1", "start_ms": 1000, "end_ms": 3000},
                {"id": "u2", "start_ms": 22000, "end_ms": 24000}]
        candidates = [{"utterance_ids": [r["id"]], "type": "engagement", "reason": "当前交战"}
                      for r in rows]
        result = select_ranges(candidates, rows, 30000)
        self.assertEqual(result["core_ranges"], [[1000, 3000], [22000, 24000]])
        self.assertEqual(result["selected_ranges"], [[0, 30000]])
        candidates[0]["utterance_ids"] = ["invented"]
        with self.assertRaises(ValueError):
            select_ranges(candidates, rows, 30000)

    def test_metrics_do_not_double_count(self):
        result = evaluation([[0, 10000], [5000, 15000]], [[10000, 20000]], 30000)
        self.assertEqual(result["overlap_ms"], 5000)
        self.assertEqual(result["time_recall"], 0.5)
        self.assertEqual(result["selected_noncombat_ms"], 10000)
        self.assertIsNone(evaluation([], [], 1000)["time_recall"])

    def test_redact_credentials_and_signed_urls(self):
        result = redact({"transcription_url": "https://example.com?signature=private",
                         "message": "failed sk-testsecret https://example.com/private",
                         "usage": {"tokens": 12}})
        self.assertNotIn("private", str(result))
        self.assertNotIn("testsecret", str(result))
        self.assertEqual(result["usage"]["tokens"], 12)


if __name__ == "__main__":
    unittest.main()
