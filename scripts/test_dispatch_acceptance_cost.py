import unittest
from decimal import Decimal
from dispatch_acceptance_cost import price


class BillingTest(unittest.TestCase):
    def call(self, **values):
        return dict(service="DashScopeVideoProvider", operation="callDetailed", model="qwen3-vl-flash", status="RETURNED", **values)

    def test_video_usage_includes_visual_tokens_and_input_tier(self):
        row = price(self.call(usage={"input_tokens": 40000, "output_tokens": 2000}), set())
        self.assertEqual(Decimal("0.018"), Decimal(row["list_price_cny"]))

    def test_unknown_failure_is_not_free(self):
        call = self.call()
        call["status"] = "FAILED_USAGE_UNKNOWN"
        self.assertEqual("", price(call, set())["list_price_cny"])

    def test_asr_polls_do_not_duplicate_audio_charge(self):
        call = dict(service="DashScopeAsrClient", operation="query", model="fun-asr-2025-11-07", status="RETURNED", remoteStatus="SUCCEEDED", remoteTaskId="remote", usage={"duration": 457})
        seen = set()
        self.assertEqual(Decimal("0.10054"), Decimal(price(call, seen)["list_price_cny"]))
        self.assertEqual("0", price(call, seen)["list_price_cny"])

    def test_embedding_uses_input_only(self):
        call = dict(service="DashScopeEmbeddingProvider", operation="embedQuery", model="text-embedding-v3", status="RETURNED", usage={"total_tokens": 120})
        self.assertEqual(Decimal("0.00006"), Decimal(price(call, set())["list_price_cny"]))


if __name__ == "__main__":
    unittest.main()
