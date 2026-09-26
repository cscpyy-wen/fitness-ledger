import unittest

from meal_schema import extract_json_object, normalize_provider_result


class MealSchemaTest(unittest.TestCase):
    def test_single_photo_is_never_promoted_above_c(self):
        result = normalize_provider_result(self.payload(), "vision-model")
        self.assertEqual("C", result["evidenceTier"])
        self.assertEqual("C", result["items"][0]["evidenceTier"])

    def test_unknown_oil_is_added_when_model_omits_it(self):
        result = normalize_provider_result(self.payload(), "vision-model")
        self.assertIn("UNKNOWN_OIL", result["items"][0]["riskFlags"])

    def test_invalid_portion_interval_is_rejected(self):
        payload = self.payload()
        payload["items"][0]["gramsMin"] = 300
        with self.assertRaises(ValueError):
            normalize_provider_result(payload, "vision-model")

    def test_markdown_fenced_json_is_extracted(self):
        self.assertEqual({"items": []}, extract_json_object('```json\n{"items": []}\n```'))

    @staticmethod
    def payload():
        return {
            "items": [
                {
                    "name": "米饭",
                    "grams": 180,
                    "gramsMin": 140,
                    "gramsMax": 230,
                    "per100g": {"kcal": 116, "carbsG": 25.9, "proteinG": 2.6, "fatG": 0.3},
                    "sourceName": "模型常见值估算",
                    "evidenceTier": "A",
                    "riskFlags": [],
                    "alternatives": ["糙米饭"],
                }
            ],
            "evidenceTier": "A",
        }


if __name__ == "__main__":
    unittest.main()
