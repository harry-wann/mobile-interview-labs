import LabKit
import PracticeSwift
import PracticePlatform
import PracticeAdvanced
import PracticeExchange
import PracticeTesting
import PracticeDesign
import PracticeCoding
import SolutionSwift
import SolutionPlatform
import SolutionAdvanced
import SolutionExchange
import SolutionTesting
import SolutionDesign
import SolutionCoding

/// App 首頁列出的所有區。新增一區時，在 Practice／Solution 的 Package.swift 加
/// target，再在這裡各加一行。
enum Catalog {
    static let practice: [LabSection] = [
        PracticeSwift.section,
        PracticePlatform.section,
        PracticeAdvanced.section,
        PracticeExchange.section,
        PracticeTesting.section,
        PracticeDesign.section,
        PracticeCoding.section,
    ]

    static let solution: [LabSection] = [
        SolutionSwift.section,
        SolutionPlatform.section,
        SolutionAdvanced.section,
        SolutionExchange.section,
        SolutionTesting.section,
        SolutionDesign.section,
        SolutionCoding.section,
    ]
}
