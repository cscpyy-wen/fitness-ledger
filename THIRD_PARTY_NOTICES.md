# Third-party notices

## Xiaomi cloud interoperability source (alpha02)

- Shared source: `xiaomi-cloud-core/src/main/kotlin`; compiled into the app and the independent probe.
- License: GPL-3.0-or-later, with complete terms bundled in `app/src/main/assets/licenses/Xiaomi-cloud-GPL-3.0.txt`.
- Upstream references, fixed revisions and local modifications: `xiaomi-probe/NOTICE` and `app/src/main/assets/licenses/Xiaomi-cloud-NOTICE.txt`.
- The release SBOM names this source component. The personal delivery includes corresponding source; it is not a public or store release. External distribution of the combined program must satisfy GPL requirements, not merely attribution. This does not replace the separate notices and licenses below.

## Google AIY Food V1

- Artifact: `app/src/main/assets/models/aiy_food_v1.tflite`
- Upstream: https://www.kaggle.com/models/google/aiy/tfLite/vision-classifier-food-v1/1
- Version: 1
- SHA-256: `03DD6D9129501F97BE00775D9E17B5D9AD13BE730149352ADB8F4CA953B7A650`
- License: Apache License 2.0. A copy is bundled at `app/src/main/assets/licenses/Apache-2.0.txt`.

The upstream model card says this classifier is not suitable for predicting ingredients, allergens, or nutrition. This app therefore uses it only to produce dish-name hypotheses. It never treats the classifier score as nutrition or accuracy.

## Google AI Edge LiteRT

- Maven coordinate: `com.google.ai.edge.litert:litert:2.2.0`
- Upstream: https://github.com/google-ai-edge/LiteRT
- License: Apache License 2.0.
- Upstream third-party notice: `app/src/main/assets/licenses/LiteRT-2.2.0-THIRD_PARTY_NOTICE.txt` (bundled in the APK).

The Apache license and LiteRT's upstream third-party notice are bundled with the app. Resolved dependency files and hashes are additionally controlled by Gradle dependency verification and the release SBOM.

## USDA FoodData Central FNDDS 2021-2023

- Upstream: https://fdc.nal.usda.gov/download-datasets/
- Release archive: `FoodData_Central_survey_food_json_2024-10-31.zip`
- Source archive SHA-256: `DFB06AE7DDC397CCD570B91C14B75438AB2BA39F64F22D321F61D4A52A77F3EB`

USDA states that FoodData Central data are public domain and do not require permission to use; attribution is retained. The app bundles only a small reviewed mapping of per-100 g values and FDC identifiers, not the full database.
