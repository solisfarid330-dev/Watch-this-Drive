import unittest
import numpy as np

from research.naip_texture_classifier import local_std, metrics, sample_scene, spatial_holdout_mask, spatial_train_test_masks, feature_bounds, feature_outside_bounds


class TextureClassifierResearchTests(unittest.TestCase):
    def test_local_std_preserves_shape_and_is_finite(self):
        image = np.arange(400, dtype=np.float32).reshape(20, 20)
        result = local_std(image, 3)
        self.assertEqual(result.shape, image.shape)
        self.assertTrue(np.all(np.isfinite(result)))
        self.assertTrue(np.all(result >= 0))

    def test_local_std_of_constant_image_is_zero(self):
        image = np.full((32, 32), 0.42, dtype=np.float32)
        result = local_std(image, 5)
        self.assertTrue(np.allclose(result, 0.0, atol=1e-6))

    def test_feature_bounds_accept_training_distribution(self):
        training = np.array([[0, 10], [1, 11], [2, 12], [3, 13]], dtype=np.float32)
        low, high = feature_bounds(training)
        inside = feature_outside_bounds(np.array([[1.5, 11.5]], dtype=np.float32), low, high)
        self.assertFalse(np.any(inside))

    def test_feature_bounds_flag_distribution_shift(self):
        training = np.array([[0, 10], [1, 11], [2, 12], [3, 13]], dtype=np.float32)
        low, high = feature_bounds(training)
        shifted = feature_outside_bounds(np.array([[100, 11.5]], dtype=np.float32), low, high)
        self.assertTrue(shifted[0, 0])
        self.assertFalse(shifted[0, 1])

    def test_metrics_reports_perfect_separation(self):
        y = np.array([0, 0, 1, 1], dtype=np.uint8)
        probability = np.array([0.05, 0.2, 0.8, 0.95])
        result = metrics(y, probability)
        self.assertEqual(result["f1"], 1.0)
        self.assertEqual(result["rough_false_positive_rate"], 0.0)
        self.assertEqual(result["fairway_recall"], 1.0)

    def test_buffered_spatial_split_excludes_neighboring_training_blocks(self):
        yy, xx = np.mgrid[0:256, 0:256]
        train, test = spatial_train_test_masks(yy.ravel(), xx.ravel(), block_px=32, buffer_blocks=1)
        train_grid = train.reshape(256, 256)
        test_grid = test.reshape(256, 256)
        self.assertTrue(np.any(test_grid))
        self.assertTrue(np.any(train_grid))
        from scipy import ndimage
        halo = ndimage.binary_dilation(test_grid, structure=np.ones((65, 65), dtype=bool))
        self.assertFalse(np.any(train_grid & halo))

    def test_spatial_sampler_keeps_blocks_disjoint(self):
        h = w = 160
        features = np.zeros((h, w, 3), dtype=np.float32)
        fairway = np.zeros((h, w), dtype=bool)
        rough = np.zeros((h, w), dtype=bool)
        # Separate labelled regions over many 64px blocks.
        fairway[8:72, 8:152] = True
        rough[88:152, 8:152] = True
        scene = {
            "features": features,
            "fairway": fairway,
            "rough": rough,
        }
        x_train, y_train, x_test, y_test = sample_scene(
            scene, np.random.default_rng(20261124), max_per_class=50000, block_px=32
        )
        self.assertGreater(len(x_train), 500)
        self.assertGreater(len(x_test), 100)
        self.assertEqual(set(np.unique(y_train)), {0, 1})
        self.assertEqual(set(np.unique(y_test)), {0, 1})
        self.assertTrue(np.all(np.isfinite(x_train)))
        self.assertTrue(np.all(np.isfinite(x_test)))
        yy, xx = np.mgrid[0:h, 0:w]
        holdout = spatial_holdout_mask(yy, xx, block_px=32)
        for row in range(0, h, 32):
            for col in range(0, w, 32):
                block = holdout[row:row+32, col:col+32]
                self.assertTrue(np.all(block == block[0, 0]))


if __name__ == "__main__":
    unittest.main()
