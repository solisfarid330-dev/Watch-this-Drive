import unittest
import numpy as np

from naip_seedless_fairway import rank_clusters, robust_z, score_corridor_pixels


class SeedlessFairwaySelectionTests(unittest.TestCase):
    def test_rank_prefers_green_low_texture_cluster_near_hole_line(self):
        ndvi = np.array([0.25, 0.72, 0.61, 0.48])
        excess_green = np.array([0.10, 0.82, 0.66, 0.35])
        texture = np.array([0.80, 0.12, 0.35, 0.50])
        distance = np.array([30.0, 4.0, 12.0, 20.0])
        scores = rank_clusters(ndvi, excess_green, texture, distance)
        self.assertEqual(int(np.argmax(scores)), 1)

    def test_selection_function_has_no_fairway_label_parameter(self):
        import inspect
        parameters = inspect.signature(rank_clusters).parameters
        self.assertEqual(
            set(parameters),
            {"ndvi", "excess_green", "texture", "centerline_distance_m"},
        )
        self.assertNotIn("fairway", parameters)
        self.assertNotIn("rough", parameters)

    def test_robust_z_handles_constant_feature_without_nan(self):
        values = robust_z(np.array([0.4, 0.4, 0.4, 0.4]))
        self.assertTrue(np.all(np.isfinite(values)))
        self.assertTrue(np.allclose(values, 0.0))

    def test_cluster_ranking_is_deterministic(self):
        features = (
            np.array([0.2, 0.7, 0.5]),
            np.array([0.1, 0.8, 0.4]),
            np.array([0.8, 0.2, 0.5]),
            np.array([25.0, 5.0, 15.0]),
        )
        first = rank_clusters(*features)
        second = rank_clusters(*features)
        self.assertTrue(np.array_equal(first, second))

    def test_rank_returns_one_finite_score_per_cluster(self):
        features = (
            np.array([0.2, 0.7, 0.5, 0.6]),
            np.array([0.1, 0.8, 0.4, 0.5]),
            np.array([0.8, 0.2, 0.5, 0.4]),
            np.array([25.0, 5.0, 15.0, 10.0]),
        )
        scores = rank_clusters(*features)
        self.assertEqual(scores.shape, (4,))
        self.assertTrue(np.all(np.isfinite(scores)))

    def test_corridor_score_never_selects_pixels_outside_analysis_mask(self):
        rows, cols = np.mgrid[0:20, 0:20]
        analysis = np.zeros((20, 20), dtype=bool)
        analysis[5:15, 4:16] = True
        ndvi = 0.2 + 0.02 * rows
        green = 0.1 + 0.03 * cols
        texture = np.ones((20, 20)) * 0.3
        distance = np.abs(cols - 10).astype(float)
        mask, score, threshold = score_corridor_pixels(
            ndvi, green, texture, distance, analysis, percentile=58, sigma=1
        )
        self.assertFalse(np.any(mask & ~analysis))
        self.assertTrue(np.all(np.isfinite(score)))
        self.assertTrue(np.isfinite(threshold))
        self.assertGreater(int(mask.sum()), 0)

    def test_corridor_score_rejects_empty_analysis_mask(self):
        values = np.ones((5, 5), dtype=float)
        with self.assertRaises(ValueError):
            score_corridor_pixels(values, values, values, values, np.zeros((5, 5), dtype=bool))

    def test_corridor_score_handles_constant_image_bands(self):
        analysis = np.ones((8, 8), dtype=bool)
        values = np.ones((8, 8), dtype=float) * 0.4
        mask, score, threshold = score_corridor_pixels(
            values, values, values, values, analysis, percentile=58, sigma=1
        )
        self.assertTrue(np.all(np.isfinite(score)))
        self.assertTrue(np.isfinite(threshold))
        self.assertEqual(mask.shape, analysis.shape)


if __name__ == "__main__":
    unittest.main()
