import unittest

from shapely.geometry import LineString, Point

from scripts.course_intelligence.geometry import local_metric_transformer, project_wgs84
from scripts.course_intelligence.association import associate_features_to_holes


class GenericGeometryTests(unittest.TestCase):
    def test_local_projection_works_in_both_hemispheres(self):
        for point in (Point(-97.1, 32.7), Point(151.2, -33.8)):
            transformer = local_metric_transformer([point])
            projected = project_wgs84(point, transformer)
            self.assertAlmostEqual(projected.x, 0.0, places=5)
            self.assertAlmostEqual(projected.y, 0.0, places=5)

    def test_association_and_ambiguity_are_course_independent(self):
        hole_a = {
            "type": "Feature", "id": "way/1",
            "geometry": {"type": "LineString", "coordinates": [[0.0, 0.0], [0.0, 0.01]]},
            "properties": {"golf": "hole", "tags": {"ref": "1"}},
            "_shape": LineString([(0.0, 0.0), (0.0, 0.01)]),
        }
        hole_b = {
            "type": "Feature", "id": "way/2",
            "geometry": {"type": "LineString", "coordinates": [[0.001, 0.0], [0.001, 0.01]]},
            "properties": {"golf": "hole", "tags": {"ref": "2"}},
            "_shape": LineString([(0.001, 0.0), (0.001, 0.01)]),
        }
        green = {
            "type": "Feature", "id": "way/3",
            "geometry": {"type": "Point", "coordinates": [0.0005, 0.005]},
            "properties": {"golf": "green"},
            "_shape": Point(0.0005, 0.005),
        }
        holes, summary = associate_features_to_holes(
            [hole_a, hole_b, green], max_distance_m=200, ambiguity_margin_m=100
        )
        self.assertEqual(len(holes), 2)
        self.assertEqual(summary["ambiguous_associations"], 1)
        self.assertEqual(
            holes[0]["properties"]["associated_features"][0]["association_status"],
            "ambiguous_nearest_centerline",
        )

    def test_no_course_specific_zone_is_required(self):
        features = [
            {"type": "Feature", "id": "h", "geometry": {"type": "LineString", "coordinates": [[151.2, -33.8], [151.2, -33.79]]},
             "properties": {"golf": "hole"}, "_shape": LineString([(151.2, -33.8), (151.2, -33.79)])},
            {"type": "Feature", "id": "g", "geometry": {"type": "Point", "coordinates": [151.2001, -33.795]},
             "properties": {"golf": "green"}, "_shape": Point(151.2001, -33.795)},
        ]
        holes, summary = associate_features_to_holes(features)
        self.assertEqual(summary["hole_centerlines"], 1)
        self.assertEqual(summary["associated_features_by_type"]["green"], 1)


if __name__ == "__main__":
    unittest.main()
