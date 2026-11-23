import importlib.util
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("course_data_audit.py")
SPEC = importlib.util.spec_from_file_location("course_data_audit", MODULE_PATH)
audit = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(audit)


class CourseDataAuditTests(unittest.TestCase):
    def test_boundary_query_uses_discovered_osm_identity(self):
        query = audit.build_overpass_query({"osm_type": "relation", "osm_id": 123456})
        self.assertIn("relation(123456);", query)
        self.assertIn('nwr(area.courseArea)["golf"]', query)
        self.assertNotIn("South Padre", query)

    def test_node_is_rejected_as_boundary(self):
        with self.assertRaises(audit.PipelineError):
            audit.build_overpass_query({"osm_type": "node", "osm_id": 9})

    def test_inventory_preserves_relation_member_count_and_tag_counts(self):
        payload = {"elements": [
            {"type": "way", "id": 1, "tags": {"golf": "green", "ref": "1"},
             "geometry": [{"lat": 1, "lon": 2}, {"lat": 1.1, "lon": 2.1}]},
            {"type": "relation", "id": 2, "tags": {"golf": "fairway"},
             "members": [{"type": "way", "ref": 3, "role": "outer"}]},
            {"type": "way", "id": 4, "tags": {"golf": "bunker"}},
        ]}
        report = audit.inventory(payload)
        self.assertEqual(report["counts_by_golf_tag"],
                         {"bunker": 1, "fairway": 1, "green": 1})
        self.assertEqual(report["features"][1]["relation_member_count"], 1)
        self.assertEqual(report["features_missing_direct_geometry"], {"bunker": 1})

    def test_non_golf_elements_are_not_counted(self):
        report = audit.inventory({"elements": [
            {"type": "way", "id": 1, "tags": {"leisure": "golf_course"}}
        ]})
        self.assertEqual(report["golf_feature_count"], 0)


    def test_multipolygon_relation_exports_outer_and_inner_rings(self):
        outer = [
            {"lat": 0, "lon": 0}, {"lat": 0, "lon": 10},
            {"lat": 10, "lon": 10}, {"lat": 10, "lon": 0},
            {"lat": 0, "lon": 0},
        ]
        inner = [
            {"lat": 2, "lon": 2}, {"lat": 8, "lon": 2},
            {"lat": 8, "lon": 8}, {"lat": 2, "lon": 8},
            {"lat": 2, "lon": 2},
        ]
        payload = {"elements": [{
            "type": "relation", "id": 55,
            "tags": {"golf": "fairway", "type": "multipolygon"},
            "members": [
                {"type": "way", "ref": 1, "role": "outer", "geometry": outer},
                {"type": "way", "ref": 2, "role": "inner", "geometry": inner},
            ],
        }]}
        result = audit.to_geojson(payload)
        self.assertEqual(len(result["features"]), 1)
        geometry = result["features"][0]["geometry"]
        self.assertEqual(geometry["type"], "Polygon")
        self.assertEqual(len(geometry["coordinates"]), 2)
        self.assertEqual(result["metadata"]["relations_or_features_omitted"], [])

    def test_multipolygon_stitches_member_way_segments(self):
        first = [{"lat": 0, "lon": 0}, {"lat": 0, "lon": 1}, {"lat": 1, "lon": 1}]
        second = [{"lat": 1, "lon": 1}, {"lat": 1, "lon": 0}, {"lat": 0, "lon": 0}]
        relation = {
            "members": [
                {"type": "way", "ref": 10, "role": "outer", "geometry": first},
                {"type": "way", "ref": 11, "role": "outer", "geometry": second},
            ]
        }
        geometry, errors = audit._relation_multipolygon_geometry(relation)
        self.assertEqual(geometry["type"], "Polygon")
        self.assertEqual(len(geometry["coordinates"][0]), 5)
        self.assertEqual(errors, [])

    def test_open_hole_way_exports_as_linestring(self):
        payload = {"elements": [{
            "type": "way", "id": 77, "tags": {"golf": "hole", "ref": "1"},
            "geometry": [{"lat": 1, "lon": 2}, {"lat": 2, "lon": 3}],
        }]}
        result = audit.to_geojson(payload)
        self.assertEqual(result["features"][0]["geometry"]["type"], "LineString")


    def test_direct_polygon_is_oriented_counterclockwise(self):
        payload = {"elements": [{
            "type": "way", "id": 88, "tags": {"golf": "green"},
            "geometry": [
                {"lat": 0, "lon": 0}, {"lat": 1, "lon": 0},
                {"lat": 1, "lon": 1}, {"lat": 0, "lon": 1},
                {"lat": 0, "lon": 0},
            ],
        }]}
        result = audit.to_geojson(payload)
        ring = result["features"][0]["geometry"]["coordinates"][0]
        self.assertGreater(audit._ring_area(ring), 0)


if __name__ == "__main__":
    unittest.main()
