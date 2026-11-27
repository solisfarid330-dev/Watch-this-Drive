import unittest
from urllib.parse import parse_qs, urlparse

from multi_source_course_audit import (
    OPEN_GOLF_API,
    SourceError,
    choose_course,
    naip_export_url,
)


class MultiSourceCourseAuditTests(unittest.TestCase):
    def test_uses_current_open_golf_api_v1_base_path(self):
        self.assertEqual(OPEN_GOLF_API, "https://api.opengolfapi.org/api/v1")

    def test_course_match_prefers_exact_name_and_nearby_reference(self):
        records = [
            {"id": "far", "name": "Howling Trails Golf Course", "latitude": 27.0, "longitude": -98.0},
            {"id": "near", "name": "Howling Trails Golf Course", "latitude": 26.2, "longitude": -98.0},
            {"id": "partial", "name": "Howling Trails", "latitude": 26.2, "longitude": -98.0},
        ]
        selected, distance = choose_course(records, "Howling Trails Golf Course", (26.2, -98.0))
        self.assertEqual(selected["id"], "near")
        self.assertAlmostEqual(distance, 0.0, places=3)

    def test_course_match_rejects_empty_candidates(self):
        with self.assertRaises(SourceError):
            choose_course([], "Unknown Course")

    def test_naip_request_uses_four_band_texas_image_server_export(self):
        url = naip_export_url(29.7, -95.4, radius_m=1000)
        parsed = urlparse(url)
        params = parse_qs(parsed.query)
        self.assertIn("NAIP22_NCCIR_60cm/ImageServer/exportImage", parsed.path)
        self.assertEqual(params["bboxSR"], ["3857"])
        self.assertEqual(params["imageSR"], ["3857"])
        self.assertEqual(params["format"], ["tiff"])
        self.assertEqual(params["size"], ["2048,2048"])
        self.assertEqual(params["f"], ["image"])
        bounds = [float(value) for value in params["bbox"][0].split(",")]
        self.assertEqual(len(bounds), 4)
        self.assertLess(bounds[0], bounds[2])
        self.assertLess(bounds[1], bounds[3])


if __name__ == "__main__":
    unittest.main()
