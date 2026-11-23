import importlib.util
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("course_data_audit.py")
SPEC = importlib.util.spec_from_file_location("course_data_audit", MODULE_PATH)
audit = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(audit)


class CourseDataAuditTests(unittest.TestCase):
    def test_query_uses_discovered_osm_identity_not_course_constants(self):
        course = {"osm_type": "relation", "osm_id": 123456}
        query = audit.build_overpass_query(course)
        self.assertIn("relation(123456);", query)
        self.assertIn('nwr(area.courseArea)["golf"]', query)
        self.assertNotIn("South Padre", query)

    def test_node_is_rejected_as_boundary(self):
        with self.assertRaises(audit.PipelineError):
            audit.build_overpass_query({"osm_type": "node", "osm_id": 9})

    def test_inventory_counts_tags_and_preserves_relation_semantics(self):
        payload = {
            "elements": [
                {"type": "way", "id": 1, "tags": {"golf": "green", "ref": "1"},
                 "geometry": [{"lat": 1, "lon": 2}, {"lat": 1.1, "lon": 2.1}]},
                {"type": "relation", "id": 2, "tags": {"golf": "fairway"},
                 "members": [{"type": "way", "ref": 3, "role": "outer"}]},
                {"type": "way", "id": 4, "tags": {"golf": "bunker"}},
            ]
        }
        report = audit.inventory(payload)
        self.assertEqual(report["counts_by_golf_tag"],
                         {"bunker": 1, "fairway": 1, "green": 1})
        self.assertEqual(report["features"][1]["relation_member_count"], 1)
        self.assertEqual(report["features_missing_direct_geometry"], {"bunker": 1})


    def test_geojson_exports_direct_geometries_and_flags_relations(self):
        payload = {"elements": [
            {"type": "way", "id": 10, "tags": {"golf": "green"},
             "geometry": [
                 {"lat": 1, "lon": 2}, {"lat": 1, "lon": 3},
                 {"lat": 2, "lon": 3}, {"lat": 1, "lon": 2}]},
            {"type": "relation", "id": 11, "tags": {"golf": "fairway"},
             "members": [{"type": "way", "ref": 12, "role": "outer",
                          "geometry": [{"lat": 1, "lon": 2}]}]},
        ]}
        result = audit.to_geojson(payload)
        self.assertEqual(result["type"], "FeatureCollection")
        self.assertEqual(len(result["features"]), 1)
        self.assertEqual(result["features"][0]["geometry"]["type"], "Polygon")
        self.assertEqual(len(result["metadata"]["relations_omitted"]), 1)

    def test_open_way_exports_as_linestring(self):
        payload = {"elements": [
            {"type": "way", "id": 20, "tags": {"golf": "hole"},
             "geometry": [{"lat": 1, "lon": 2}, {"lat": 2, "lon": 3}]}
        ]}
        result = audit.to_geojson(payload)
        self.assertEqual(result["features"][0]["geometry"]["type"], "LineString")

    def test_non_golf_elements_are_not_counted(self):
        report = audit.inventory({"elements": [
            {"type": "way", "id": 1, "tags": {"leisure": "golf_course"}}
        ]})
        self.assertEqual(report["golf_feature_count"], 0)


if __name__ == "__main__":
    unittest.main()
