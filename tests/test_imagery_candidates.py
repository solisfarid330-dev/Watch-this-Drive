import unittest

import numpy as np
from rasterio.transform import from_origin

from scripts.course_intelligence.imagery_candidates import extract_imagery_candidates


class ImageryCandidateExtractionTests(unittest.TestCase):
    def setUp(self):
        # 2 m pixels; the synthetic course contains a vegetated patch and
        # a bright low-NDVI patch surrounded by zero/no-data pixels.
        self.image = np.zeros((60, 60, 4), dtype=np.uint8)
        self.image[8:28, 8:28] = [40, 85, 35, 190]
        self.image[35:50, 35:50] = [180, 165, 135, 100]
        self.transform = from_origin(500000, 3600000, 2, 2)
        self.mask = np.zeros((60, 60), dtype=bool)
        self.mask[5:55, 5:55] = True

    def test_emits_generic_turf_and_bare_ground_candidates(self):
        collection, summary = extract_imagery_candidates(
            self.image, self.transform, course_mask=self.mask,
            min_area_m2=20, max_area_m2=5000,
        )
        types = {f["properties"]["candidate_type"] for f in collection["features"]}
        self.assertEqual(types, {
            "vegetated_turf_candidate", "bare_ground_candidate"
        })
        self.assertEqual(summary["candidate_count"], 2)
        for feature in collection["features"]:
            props = feature["properties"]
            self.assertEqual(props["confidence"], "low")
            self.assertEqual(props["validation_status"], "unreviewed")
            self.assertIsNone(props["semantic_golf_feature"])
            self.assertTrue(props["not_ground_truth"])
            self.assertIn("compactness", props["shape_metrics"])
            self.assertIn("solidity", props["shape_metrics"])
            self.assertIn("oriented_bbox_elongation", props["shape_metrics"])
            self.assertIn("ndvi_mean", props["spectral_metrics"])
            self.assertGreater(props["spectral_metrics"]["pixel_count"], 0)

    def test_course_mask_excludes_pixels_outside_course(self):
        mask = np.zeros((60, 60), dtype=bool)
        collection, summary = extract_imagery_candidates(
            self.image, self.transform, course_mask=mask,
            min_area_m2=20, max_area_m2=5000,
        )
        self.assertEqual(collection["features"], [])
        self.assertEqual(summary["candidate_count"], 0)

    def test_rejects_mismatched_course_mask(self):
        with self.assertRaises(ValueError):
            extract_imagery_candidates(
                self.image, self.transform, course_mask=np.ones((2, 2), dtype=bool)
            )

    def test_requires_four_band_input(self):
        with self.assertRaises(ValueError):
            extract_imagery_candidates(self.image[:, :, :3], self.transform)


if __name__ == "__main__":
    unittest.main()
